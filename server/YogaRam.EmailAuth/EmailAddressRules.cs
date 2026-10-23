using System.Net.Mail;

namespace YogaRam.EmailAuth;

public static class EmailAddressRules
{
    public static bool TryNormalize(string? input, out string email)
    {
        email = string.Empty;
        if (string.IsNullOrWhiteSpace(input))
        {
            return false;
        }

        var candidate = input.Trim();
        if (candidate.Length > 254 || candidate.Contains(' ') || candidate.Contains('\n') || candidate.Contains('\r'))
        {
            return false;
        }

        try
        {
            var parsed = new MailAddress(candidate);
            if (!string.Equals(parsed.Address, candidate, StringComparison.OrdinalIgnoreCase))
            {
                return false;
            }

            var at = parsed.Address.LastIndexOf('@');
            if (at <= 0 || at == parsed.Address.Length - 1 || !parsed.Address[(at + 1)..].Contains('.'))
            {
                return false;
            }

            email = parsed.Address.ToLowerInvariant();
            return true;
        }
        catch (FormatException)
        {
            return false;
        }
    }

    public static string Mask(string normalizedEmail)
    {
        var at = normalizedEmail.IndexOf('@');
        var local = normalizedEmail[..at];
        var domain = normalizedEmail[(at + 1)..];
        var hidden = local.Length <= 2
            ? local[..1] + "***"
            : local[..1] + "***" + local[^1];
        return hidden + "@" + domain;
    }
}
