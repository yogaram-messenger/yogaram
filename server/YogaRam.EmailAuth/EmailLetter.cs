namespace YogaRam.EmailAuth;

public enum EmailLetterKind
{
    ConfirmRegistration,
    SignIn,
    ResetPassword
}

public sealed record EmailLetter(string Subject, string Body);

public static class EmailLetterWriter
{
    public static EmailLetter Write(EmailLetterKind kind, string code)
    {
        var subject = kind switch
        {
            EmailLetterKind.ResetPassword => "Восстановление пароля Nandagram",
            EmailLetterKind.SignIn => "Код входа Nandagram",
            _ => "Подтверждение почты Nandagram"
        };

        var lead = kind switch
        {
            EmailLetterKind.ResetPassword => "Код для нового пароля Nandagram",
            EmailLetterKind.SignIn => "Код для входа в Nandagram",
            _ => "Код подтверждения регистрации Nandagram"
        };

        var body = lead + ": " + code + "\n\n"
            + "Код действует 10 минут. Никому его не сообщайте, даже если просят от имени Nandagram.\n"
            + "Если вы не запрашивали код, проигнорируйте это письмо.\n";
        return new EmailLetter(subject, body);
    }
}
