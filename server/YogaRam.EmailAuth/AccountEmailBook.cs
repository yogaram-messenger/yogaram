using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace YogaRam.EmailAuth;

public enum EmailCodeStatus
{
    Success,
    NotFound,
    Invalid,
    Expired,
    TooManyAttempts,
    EmailTaken,
    NotConfirmed
}

public sealed class AccountEmailBook
{
    public const int MaxAttempts = 5;

    private readonly string? _path;
    private readonly object _gate = new();
    private State _state;
    private FileStream? _lockStream;

    public AccountEmailBook(string? path = null)
    {
        _path = string.IsNullOrWhiteSpace(path) ? null : path;
        _state = new State();
        if (_path != null && File.Exists(_path))
        {
            var loaded = JsonSerializer.Deserialize<State>(File.ReadAllText(_path));
            if (loaded != null)
            {
                _state = loaded;
            }
        }
    }

    public void BeginRegistration(string phone, string phoneCodeHash, DateTimeOffset now, TimeSpan pendingFor, long userId = 0)
    {
        Mutate(state =>
        {
            state.Phones[phone] = new PhoneEntry
            {
                PhoneCodeHash = phoneCodeHash,
                PendingUntil = now.Add(pendingFor),
                UserId = userId
            };
        });
    }

    public EmailCodeStatus IssueLoginCode(string phone, string phoneCodeHash, string email, string code, DateTimeOffset now, TimeSpan codeTtl)
    {
        var status = EmailCodeStatus.NotFound;
        Mutate(state =>
        {
            if (!state.Phones.TryGetValue(phone, out var phoneEntry)
                || phoneEntry.PhoneCodeHash != phoneCodeHash
                || (phoneEntry.PendingUntil < now && !phoneEntry.Confirmed))
            {
                status = EmailCodeStatus.NotFound;
                return;
            }

            if (phoneEntry.Confirmed)
            {
                status = EmailCodeStatus.Success;
                return;
            }

            if (state.EmailToPhone.TryGetValue(email, out var ownerPhone) && ownerPhone != phone)
            {
                if (ownerPhone.StartsWith("user:", StringComparison.Ordinal))
                {
                    status = EmailCodeStatus.EmailTaken;
                    return;
                }

                if (state.Phones.TryGetValue(ownerPhone, out var owner)
                    && (owner.Confirmed || owner.CodeExpires > now))
                {
                    status = EmailCodeStatus.EmailTaken;
                    return;
                }
            }

            phoneEntry.Email = email;
            phoneEntry.CodeSalt = Convert.ToHexString(RandomNumberGenerator.GetBytes(16));
            phoneEntry.CodeHash = Hash(code, phoneEntry.CodeSalt);
            phoneEntry.CodeExpires = now.Add(codeTtl);
            phoneEntry.Attempts = 0;
            phoneEntry.Confirmed = false;
            phoneEntry.PendingUntil = now.Add(codeTtl);
            state.EmailToPhone[email] = phone;
            status = EmailCodeStatus.Success;
        });
        return status;
    }

    public EmailCodeStatus ConfirmLoginCode(string phone, string phoneCodeHash, string code, DateTimeOffset now)
    {
        var status = EmailCodeStatus.NotFound;
        Mutate(state =>
        {
            status = CheckPhoneCode(state, phone, phoneCodeHash, code, now, confirm: true);
        });
        return status;
    }

    public void AttachNewUser(string phone, long userId)
    {
        Mutate(state =>
        {
            if (!state.Phones.TryGetValue(phone, out var phoneEntry) || !phoneEntry.Confirmed || phoneEntry.Email == null)
            {
                return;
            }

            phoneEntry.UserId = userId;
            state.Users[userId.ToString()] = new UserEntry
            {
                Email = phoneEntry.Email
            };
            state.EmailToPhone[phoneEntry.Email] = "user:" + userId;
        });
    }

    public bool TryGetConfirmedEmail(string phone, out string email)
    {
        string? found = null;
        Read(state =>
        {
            if (state.Phones.TryGetValue(phone, out var phoneEntry) && phoneEntry.Confirmed && phoneEntry.Email != null)
            {
                found = phoneEntry.Email;
            }
        });
        email = found ?? string.Empty;
        return found != null;
    }

    public bool IsLoginConfirmed(string phone, string phoneCodeHash)
    {
        var confirmed = false;
        Read(state =>
        {
            confirmed = state.Phones.TryGetValue(phone, out var phoneEntry)
                && phoneEntry.PhoneCodeHash == phoneCodeHash
                && phoneEntry.Confirmed
                && !string.IsNullOrEmpty(phoneEntry.Email);
        });
        return confirmed;
    }

    public bool TryGetLoginEmail(string phone, string phoneCodeHash, DateTimeOffset now, out string email, out bool confirmed)
    {
        string? found = null;
        var isConfirmed = false;
        Read(state =>
        {
            if (!state.Phones.TryGetValue(phone, out var phoneEntry)
                || phoneEntry.PhoneCodeHash != phoneCodeHash
                || string.IsNullOrEmpty(phoneEntry.Email)
                || (phoneEntry.PendingUntil < now && !phoneEntry.Confirmed))
            {
                return;
            }

            found = phoneEntry.Email;
            isConfirmed = phoneEntry.Confirmed;
        });
        email = found ?? string.Empty;
        confirmed = isConfirmed;
        return found != null;
    }

    public long RegistrationUserId(string phone, string phoneCodeHash)
    {
        long found = 0;
        Read(state =>
        {
            if (state.Phones.TryGetValue(phone, out var phoneEntry) && phoneEntry.PhoneCodeHash == phoneCodeHash)
            {
                found = phoneEntry.UserId;
            }
        });
        return found;
    }

    public bool TryGetUserEmail(long userId, out string email)
    {
        string? found = null;
        Read(state =>
        {
            if (state.Users.TryGetValue(userId.ToString(), out var user))
            {
                found = user.Email;
            }
        });
        email = found ?? string.Empty;
        return found != null;
    }

    public void BindSession(long permAuthKeyId, long userId)
    {
        if (permAuthKeyId == 0 || userId == 0)
        {
            return;
        }

        Mutate(state => state.Sessions[permAuthKeyId.ToString()] = userId);
    }

    public bool TryGetSessionUser(long permAuthKeyId, out long userId)
    {
        long found = 0;
        Read(state =>
        {
            if (state.Sessions.TryGetValue(permAuthKeyId.ToString(), out var stored))
            {
                found = stored;
            }
        });
        userId = found;
        return found != 0;
    }

    public void RememberUserEmail(long userId, string email)
    {
        Mutate(state =>
        {
            if (!state.Users.TryGetValue(userId.ToString(), out var user))
            {
                user = new UserEntry();
                state.Users[userId.ToString()] = user;
            }

            user.Email = email;
            state.EmailToPhone[email] = "user:" + userId;
        });
    }

    public EmailCodeStatus IssueRecoveryCode(long userId, string code, DateTimeOffset now, TimeSpan ttl)
    {
        var status = EmailCodeStatus.NotConfirmed;
        Mutate(state =>
        {
            if (!state.Users.TryGetValue(userId.ToString(), out var user) || string.IsNullOrEmpty(user.Email))
            {
                status = EmailCodeStatus.NotConfirmed;
                return;
            }

            user.RecoverySalt = Convert.ToHexString(RandomNumberGenerator.GetBytes(16));
            user.RecoveryHash = Hash(code, user.RecoverySalt);
            user.RecoveryExpires = now.Add(ttl);
            user.RecoveryAttempts = 0;
            user.RecoveryUsed = false;
            status = EmailCodeStatus.Success;
        });
        return status;
    }

    public EmailCodeStatus CheckRecoveryCode(long userId, string code, DateTimeOffset now, bool consume)
    {
        var status = EmailCodeStatus.NotFound;
        Mutate(state =>
        {
            if (!state.Users.TryGetValue(userId.ToString(), out var user) || user.RecoveryHash == null || user.RecoverySalt == null)
            {
                status = EmailCodeStatus.NotFound;
                return;
            }

            if (user.RecoveryUsed)
            {
                status = EmailCodeStatus.Invalid;
                return;
            }

            if (user.RecoveryExpires < now)
            {
                status = EmailCodeStatus.Expired;
                return;
            }

            if (user.RecoveryAttempts >= MaxAttempts)
            {
                status = EmailCodeStatus.TooManyAttempts;
                return;
            }

            if (!string.Equals(user.RecoveryHash, Hash(code, user.RecoverySalt), StringComparison.Ordinal))
            {
                user.RecoveryAttempts++;
                status = user.RecoveryAttempts >= MaxAttempts
                    ? EmailCodeStatus.TooManyAttempts
                    : EmailCodeStatus.Invalid;
                return;
            }

            if (consume)
            {
                user.RecoveryUsed = true;
            }

            status = EmailCodeStatus.Success;
        });
        return status;
    }

    private static EmailCodeStatus CheckPhoneCode(State state, string phone, string phoneCodeHash, string code, DateTimeOffset now, bool confirm)
    {
        if (!state.Phones.TryGetValue(phone, out var phoneEntry)
            || phoneEntry.PhoneCodeHash != phoneCodeHash
            || phoneEntry.CodeHash == null
            || phoneEntry.CodeSalt == null)
        {
            return EmailCodeStatus.NotFound;
        }

        if (phoneEntry.CodeExpires < now)
        {
            return EmailCodeStatus.Expired;
        }

        if (phoneEntry.Attempts >= MaxAttempts)
        {
            return EmailCodeStatus.TooManyAttempts;
        }

        if (!string.Equals(phoneEntry.CodeHash, Hash(code, phoneEntry.CodeSalt), StringComparison.Ordinal))
        {
            phoneEntry.Attempts++;
            return phoneEntry.Attempts >= MaxAttempts
                ? EmailCodeStatus.TooManyAttempts
                : EmailCodeStatus.Invalid;
        }

        if (confirm)
        {
            phoneEntry.Confirmed = true;
            if (phoneEntry.UserId != 0 && phoneEntry.Email != null)
            {
                state.Users[phoneEntry.UserId.ToString()] = new UserEntry { Email = phoneEntry.Email };
            }
        }

        return EmailCodeStatus.Success;
    }

    private static string Hash(string code, string salt)
    {
        var bytes = SHA256.HashData(Encoding.UTF8.GetBytes(salt + ":" + code));
        return Convert.ToHexString(bytes);
    }

    private void Read(Action<State> action)
    {
        lock (_gate)
        {
            using (EnterFileLock())
            {
                Reload();
                action(_state);
            }
        }
    }

    private void Mutate(Action<State> action)
    {
        lock (_gate)
        {
            using (EnterFileLock())
            {
                Reload();
                action(_state);
                Save();
            }
        }
    }

    private Unlocker? EnterFileLock()
    {
        if (_path == null || (!OperatingSystem.IsLinux() && !OperatingSystem.IsWindows()))
        {
            return null;
        }

        var directory = Path.GetDirectoryName(_path);
        if (!string.IsNullOrEmpty(directory))
        {
            Directory.CreateDirectory(directory);
        }

        _lockStream ??= new FileStream(_path + ".lock", FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.ReadWrite);
#pragma warning disable CA1416
        _lockStream.Lock(0, 1);
#pragma warning restore CA1416
        return new Unlocker(_lockStream);
    }

    private sealed class Unlocker(FileStream stream) : IDisposable
    {
        public void Dispose()
        {
            try
            {
#pragma warning disable CA1416
                stream.Unlock(0, 1);
#pragma warning restore CA1416
            }
            catch (IOException)
            {
            }
        }
    }

    private void Reload()
    {
        if (_path == null || !File.Exists(_path))
        {
            return;
        }

        var loaded = JsonSerializer.Deserialize<State>(File.ReadAllText(_path));
        if (loaded != null)
        {
            _state = loaded;
        }
    }

    private void Save()
    {
        if (_path == null)
        {
            return;
        }

        var directory = Path.GetDirectoryName(_path);
        if (!string.IsNullOrEmpty(directory))
        {
            Directory.CreateDirectory(directory);
        }

        var json = JsonSerializer.Serialize(_state);
        var temporary = _path + ".tmp";
        File.WriteAllText(temporary, json);
        File.Move(temporary, _path, overwrite: true);
    }

    private sealed class State
    {
        public Dictionary<string, PhoneEntry> Phones { get; set; } = new();
        public Dictionary<string, UserEntry> Users { get; set; } = new();
        public Dictionary<string, string> EmailToPhone { get; set; } = new();
        public Dictionary<string, long> Sessions { get; set; } = new();
    }

    private sealed class PhoneEntry
    {
        public string PhoneCodeHash { get; set; } = string.Empty;
        public DateTimeOffset PendingUntil { get; set; }
        public string? Email { get; set; }
        public string? CodeHash { get; set; }
        public string? CodeSalt { get; set; }
        public DateTimeOffset? CodeExpires { get; set; }
        public int Attempts { get; set; }
        public bool Confirmed { get; set; }
        public long UserId { get; set; }
    }

    private sealed class UserEntry
    {
        public string Email { get; set; } = string.Empty;
        public string? RecoveryHash { get; set; }
        public string? RecoverySalt { get; set; }
        public DateTimeOffset? RecoveryExpires { get; set; }
        public int RecoveryAttempts { get; set; }
        public bool RecoveryUsed { get; set; }
    }
}
