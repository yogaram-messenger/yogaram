using YogaRam.EmailAuth;

namespace MyTelegram.Messenger.Services.EmailAuth;

public sealed class NandagramEmailGateway : ISingletonDependency
{
    public static readonly TimeSpan CodeLifetime = TimeSpan.FromMinutes(10);

    private readonly AccountEmailBook _book;
    private readonly IEmailSender _sender;
    private readonly ILogger<NandagramEmailGateway> _logger;
    private readonly IOptionsMonitor<MyTelegramMessengerServerOptions> _options;

    public NandagramEmailGateway(
        IOptionsMonitor<MyTelegramMessengerServerOptions> options,
        ILogger<NandagramEmailGateway> logger)
    {
        _options = options;
        _logger = logger;
        var email = options.CurrentValue.Email ?? new EmailDeliveryOptions();
        var path = string.IsNullOrWhiteSpace(email.StorePath)
            ? Path.Combine(AppContext.BaseDirectory, "data", "nandagram-emails.json")
            : email.StorePath;
        _book = new AccountEmailBook(path);
        _sender = new SmtpEmailSender(new SmtpEmailSettings
        {
            Host = email.Host,
            Port = email.Port == 0 ? 587 : email.Port,
            EnableSsl = email.EnableSsl,
            UserName = email.UserName,
            Password = email.Password,
            FromAddress = email.FromAddress,
            FromName = string.IsNullOrWhiteSpace(email.FromName) ? "Nandagram" : email.FromName
        });
    }

    public void BeginRegistration(string phone, string phoneCodeHash, long userId)
    {
        _book.BeginRegistration(phone, phoneCodeHash, DateTimeOffset.UtcNow, CodeLifetime, userId);
    }

    public void AttachNewUser(string phone, long userId)
    {
        _book.AttachNewUser(phone.ToPhoneNumber(), userId);
    }

    public bool IsLoginConfirmed(string phone, string phoneCodeHash)
    {
        return _book.IsLoginConfirmed(phone, phoneCodeHash);
    }

    public EmailCodeStatus IssueLoginCode(string phone, string phoneCodeHash, string email, string code)
    {
        return _book.IssueLoginCode(phone, phoneCodeHash, email, code, DateTimeOffset.UtcNow, CodeLifetime);
    }

    public EmailCodeStatus ConfirmLoginCode(string phone, string phoneCodeHash, string code)
    {
        return _book.ConfirmLoginCode(phone, phoneCodeHash, code, DateTimeOffset.UtcNow);
    }

    public bool TryGetConfirmedEmail(string phone, out string email)
    {
        return _book.TryGetConfirmedEmail(phone, out email);
    }

    public bool TryGetUserEmail(long userId, out string email)
    {
        return _book.TryGetUserEmail(userId, out email);
    }

    public bool TryGetLoginEmail(string phone, string phoneCodeHash, out string email, out bool confirmed)
    {
        return _book.TryGetLoginEmail(phone, phoneCodeHash, DateTimeOffset.UtcNow, out email, out confirmed);
    }

    public long RegistrationUserId(string phone, string phoneCodeHash)
    {
        return _book.RegistrationUserId(phone, phoneCodeHash);
    }

    public void BindSession(long permAuthKeyId, long userId)
    {
        _book.BindSession(permAuthKeyId, userId);
    }

    public bool TryGetSessionUser(long permAuthKeyId, out long userId)
    {
        return _book.TryGetSessionUser(permAuthKeyId, out userId);
    }

    public void RememberUserEmail(long userId, string email)
    {
        _book.RememberUserEmail(userId, email);
    }

    public EmailCodeStatus IssueRecoveryCode(long userId, string code)
    {
        return _book.IssueRecoveryCode(userId, code, DateTimeOffset.UtcNow, CodeLifetime);
    }

    public EmailCodeStatus CheckRecoveryCode(long userId, string code, bool consume)
    {
        return _book.CheckRecoveryCode(userId, code, DateTimeOffset.UtcNow, consume);
    }

    public async Task<string?> ResolveMailboxAsync(IQueryProcessor queryProcessor, IUserReadModel? user, string phone)
    {
        if (user != null)
        {
            var password = await queryProcessor.ProcessAsync(new GetUserPasswordQuery(user.UserId));
            if (EmailAddressRules.TryNormalize(password?.RecoveryEmail, out var recovery))
            {
                _book.RememberUserEmail(user.UserId, recovery);
                return recovery;
            }

            if (_book.TryGetUserEmail(user.UserId, out var bound))
            {
                return bound;
            }
        }

        if (_book.TryGetConfirmedEmail(phone, out var confirmed))
        {
            return confirmed;
        }

        return null;
    }

    public async Task<string?> ResolveRecoveryMailboxAsync(IQueryProcessor queryProcessor, long userId)
    {
        var password = await queryProcessor.ProcessAsync(new GetUserPasswordQuery(userId));
        if (EmailAddressRules.TryNormalize(password?.RecoveryEmail, out var recovery))
        {
            _book.RememberUserEmail(userId, recovery);
            return recovery;
        }

        if (_book.TryGetUserEmail(userId, out var bound))
        {
            return bound;
        }

        return null;
    }

    public async Task DeliverAsync(string email, EmailLetterKind kind, string code)
    {
        try
        {
            await _sender.SendAsync(email, kind, code);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "SMTP delivery failed for {Mailbox}", EmailAddressRules.Mask(email));
            new RpcError(500, "EMAIL_SEND_FAILED").ThrowRpcError();
        }
    }

    public async Task StoreAppCodeAsync(ICommandBus commandBus, IRequestInput input, string phone, string phoneCodeHash, string code, long userId)
    {
        var command = new SendAppCodeCommand(
            AppCodeId.Create(phone, phoneCodeHash),
            input.ToRequestInfo() with { UserId = userId },
            userId,
            phone,
            code,
            phoneCodeHash,
            DateTime.UtcNow.ToTimestamp());
        await commandBus.PublishAsync(command);
    }

    public int ResendTimeoutSeconds => Math.Max(_options.CurrentValue.VerificationCodeExpirationSeconds, 60);

    public static void ThrowIfLoginFailed(EmailCodeStatus status)
    {
        switch (status)
        {
            case EmailCodeStatus.Success:
                return;
            case EmailCodeStatus.EmailTaken:
                RpcErrors.RpcErrors400.EmailNotAllowed.ThrowRpcError();
                break;
            case EmailCodeStatus.Expired:
                RpcErrors.RpcErrors400.EmailVerifyExpired.ThrowRpcError();
                break;
            case EmailCodeStatus.TooManyAttempts:
            case EmailCodeStatus.NotFound:
                RpcErrors.RpcErrors400.PhoneHashExpired.ThrowRpcError();
                break;
            default:
                RpcErrors.RpcErrors400.CodeInvalid.ThrowRpcError();
                break;
        }
    }

    public static void ThrowIfRecoveryFailed(EmailCodeStatus status)
    {
        switch (status)
        {
            case EmailCodeStatus.Success:
                return;
            case EmailCodeStatus.Expired:
                RpcErrors.RpcErrors400.PasswordRecoveryExpired.ThrowRpcError();
                break;
            case EmailCodeStatus.NotConfirmed:
            case EmailCodeStatus.NotFound:
                RpcErrors.RpcErrors400.PasswordRecoveryNa.ThrowRpcError();
                break;
            default:
                RpcErrors.RpcErrors400.CodeInvalid.ThrowRpcError();
                break;
        }
    }
}
