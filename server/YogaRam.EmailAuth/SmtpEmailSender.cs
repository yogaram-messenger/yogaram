using System.Net;
using System.Net.Mail;

namespace YogaRam.EmailAuth;

public sealed class SmtpEmailSettings
{
    public string? Host { get; init; }
    public int Port { get; init; } = 587;
    public bool EnableSsl { get; init; } = true;
    public string? UserName { get; init; }
    public string? Password { get; init; }
    public string? FromAddress { get; init; }
    public string FromName { get; init; } = "Nandagram";
}

public interface IEmailSender
{
    Task SendAsync(string to, EmailLetterKind kind, string code, CancellationToken cancellationToken = default);
}

public sealed class SmtpEmailSender(SmtpEmailSettings settings) : IEmailSender
{
    public async Task SendAsync(string to, EmailLetterKind kind, string code, CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(settings.Host) || string.IsNullOrWhiteSpace(settings.FromAddress))
        {
            throw new InvalidOperationException("SMTP host and from-address are required.");
        }

        var letter = EmailLetterWriter.Write(kind, code);
        using var message = new MailMessage
        {
            From = new MailAddress(settings.FromAddress, settings.FromName),
            Subject = letter.Subject,
            Body = letter.Body
        };
        message.To.Add(to);

        using var client = new SmtpClient(settings.Host, settings.Port)
        {
            EnableSsl = settings.EnableSsl,
            DeliveryMethod = SmtpDeliveryMethod.Network
        };
        if (!string.IsNullOrEmpty(settings.UserName))
        {
            client.Credentials = new NetworkCredential(settings.UserName, settings.Password);
        }

        cancellationToken.ThrowIfCancellationRequested();
        await client.SendMailAsync(message, cancellationToken);
    }
}
