using Xunit;
using YogaRam.EmailAuth;

namespace YogaRam.EmailAuth.Tests;

public class RegistrationTests
{
    private static readonly DateTimeOffset Now = new(2026, 10, 23, 9, 0, 0, TimeSpan.Zero);
    private static readonly TimeSpan TenMinutes = TimeSpan.FromMinutes(10);

    [Theory]
    [InlineData("User@Example.com", "user@example.com")]
    [InlineData(" a.b@nandagram.myfbt.ru ", "a.b@nandagram.myfbt.ru")]
    public void Accepts_a_normal_mailbox(string input, string expected)
    {
        Assert.True(EmailAddressRules.TryNormalize(input, out var email));
        Assert.Equal(expected, email);
    }

    [Theory]
    [InlineData("")]
    [InlineData("not-an-email")]
    [InlineData("a@localhost")]
    [InlineData("a b@example.com")]
    [InlineData("name <a@example.com>")]
    public void Rejects_a_mailbox_that_cannot_receive_mail(string input)
    {
        Assert.False(EmailAddressRules.TryNormalize(input, out _));
    }

    [Fact]
    public void Masks_the_local_part()
    {
        Assert.Equal("a***m@example.com", EmailAddressRules.Mask("adam@example.com"));
        Assert.Equal("a***@ex.com", EmailAddressRules.Mask("ab@ex.com"));
    }

    [Fact]
    public void Confirmation_letter_contains_the_code_and_the_product_name()
    {
        var letter = EmailLetterWriter.Write(EmailLetterKind.ConfirmRegistration, "48291");
        Assert.Contains("48291", letter.Body);
        Assert.Contains("Nandagram", letter.Subject);
        Assert.Contains("Nandagram", letter.Body);
        Assert.DoesNotContain("Telegram", letter.Body);
    }

    [Fact]
    public void Confirms_registration_and_resets_the_password_through_the_same_mailbox()
    {
        var book = new AccountEmailBook();
        book.BeginRegistration("79990001122", "hash-1", Now, TenMinutes);
        Assert.Equal(EmailCodeStatus.Success, book.IssueLoginCode("79990001122", "hash-1", "user@example.com", "48291", Now, TenMinutes));
        Assert.Equal(EmailCodeStatus.Invalid, book.ConfirmLoginCode("79990001122", "hash-1", "00000", Now));
        Assert.Equal(EmailCodeStatus.Success, book.ConfirmLoginCode("79990001122", "hash-1", "48291", Now));
        Assert.True(book.TryGetConfirmedEmail("79990001122", out var email));
        Assert.Equal("user@example.com", email);

        Assert.Equal(EmailCodeStatus.NotConfirmed, book.IssueRecoveryCode(42, "11111", Now, TenMinutes));
        book.AttachNewUser("79990001122", 42);
        Assert.True(book.TryGetUserEmail(42, out var bound));
        Assert.Equal("user@example.com", bound);

        Assert.Equal(EmailCodeStatus.Success, book.IssueRecoveryCode(42, "90771", Now, TenMinutes));
        Assert.Equal(EmailCodeStatus.Success, book.CheckRecoveryCode(42, "90771", Now, consume: false));
        Assert.Equal(EmailCodeStatus.Success, book.CheckRecoveryCode(42, "90771", Now, consume: true));
        Assert.Equal(EmailCodeStatus.Invalid, book.CheckRecoveryCode(42, "90771", Now, consume: true));
    }

    [Fact]
    public void Expired_and_exhausted_codes_do_not_confirm()
    {
        var book = new AccountEmailBook();
        book.BeginRegistration("79990001122", "hash-1", Now, TenMinutes);
        book.IssueLoginCode("79990001122", "hash-1", "user@example.com", "48291", Now, TenMinutes);
        Assert.Equal(EmailCodeStatus.Expired, book.ConfirmLoginCode("79990001122", "hash-1", "48291", Now.AddMinutes(11)));

        book.BeginRegistration("79990001123", "hash-2", Now, TenMinutes);
        book.IssueLoginCode("79990001123", "hash-2", "other@example.com", "11111", Now, TenMinutes);
        for (var attempt = 0; attempt < 4; attempt++)
        {
            Assert.Equal(EmailCodeStatus.Invalid, book.ConfirmLoginCode("79990001123", "hash-2", "00000", Now));
        }

        Assert.Equal(EmailCodeStatus.TooManyAttempts, book.ConfirmLoginCode("79990001123", "hash-2", "00000", Now));
        Assert.Equal(EmailCodeStatus.TooManyAttempts, book.ConfirmLoginCode("79990001123", "hash-2", "11111", Now));
    }

    [Fact]
    public void A_confirmed_mailbox_cannot_be_taken_by_another_phone()
    {
        var book = new AccountEmailBook();
        book.BeginRegistration("79990001122", "hash-1", Now, TenMinutes);
        book.IssueLoginCode("79990001122", "hash-1", "user@example.com", "48291", Now, TenMinutes);
        book.ConfirmLoginCode("79990001122", "hash-1", "48291", Now);
        book.AttachNewUser("79990001122", 7);

        book.BeginRegistration("79990001123", "hash-2", Now, TenMinutes);
        Assert.Equal(
            EmailCodeStatus.EmailTaken,
            book.IssueLoginCode("79990001123", "hash-2", "user@example.com", "11111", Now, TenMinutes));
    }

    [Fact]
    public void The_book_survives_a_restart()
    {
        var path = Path.Combine(Path.GetTempPath(), "yogaram-email-" + Guid.NewGuid().ToString("N") + ".json");
        var book = new AccountEmailBook(path);
        book.BeginRegistration("79990001122", "hash-1", Now, TenMinutes);
        book.IssueLoginCode("79990001122", "hash-1", "user@example.com", "48291", Now, TenMinutes);
        book.ConfirmLoginCode("79990001122", "hash-1", "48291", Now);
        book.AttachNewUser("79990001122", 42);

        var reloaded = new AccountEmailBook(path);
        Assert.True(reloaded.TryGetUserEmail(42, out var email));
        Assert.Equal("user@example.com", email);
        Assert.Equal(EmailCodeStatus.Success, reloaded.IssueRecoveryCode(42, "90771", Now, TenMinutes));
        Assert.Equal(EmailCodeStatus.Success, reloaded.CheckRecoveryCode(42, "90771", Now, consume: true));
        File.Delete(path);
    }

    [Fact]
    public void Unknown_hash_and_empty_recovery_are_rejected()
    {
        var book = new AccountEmailBook();
        Assert.Equal(EmailCodeStatus.NotFound, book.ConfirmLoginCode("79990001122", "missing", "48291", Now));
        Assert.Equal(EmailCodeStatus.NotFound, book.CheckRecoveryCode(99, "48291", Now, consume: true));
    }

    [Fact]
    public void A_login_session_remembers_which_account_is_resetting_the_password()
    {
        var book = new AccountEmailBook();
        book.BindSession(9001, 42);
        Assert.True(book.TryGetSessionUser(9001, out var userId));
        Assert.Equal(42, userId);
        Assert.False(book.TryGetSessionUser(1, out _));
    }

    [Fact]
    public void A_pending_mailbox_can_be_read_until_the_code_expires()
    {
        var book = new AccountEmailBook();
        book.BeginRegistration("79990001122", "hash-1", Now, TenMinutes, userId: 7);
        Assert.Equal(7, book.RegistrationUserId("79990001122", "hash-1"));
        Assert.False(book.TryGetLoginEmail("79990001122", "hash-1", Now, out _, out _));

        book.IssueLoginCode("79990001122", "hash-1", "user@example.com", "48291", Now, TenMinutes);
        Assert.True(book.TryGetLoginEmail("79990001122", "hash-1", Now, out var email, out var confirmed));
        Assert.Equal("user@example.com", email);
        Assert.False(confirmed);
        Assert.False(book.IsLoginConfirmed("79990001122", "hash-1"));

        book.ConfirmLoginCode("79990001122", "hash-1", "48291", Now);
        Assert.True(book.IsLoginConfirmed("79990001122", "hash-1"));
        Assert.True(book.TryGetLoginEmail("79990001122", "hash-1", Now.AddMinutes(30), out _, out confirmed));
        Assert.True(confirmed);
    }
}
