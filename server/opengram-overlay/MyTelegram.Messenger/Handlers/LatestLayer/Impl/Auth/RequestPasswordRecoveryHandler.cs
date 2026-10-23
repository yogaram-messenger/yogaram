// ReSharper disable All

namespace MyTelegram.Messenger.Handlers.LatestLayer.Impl.Auth;

///<summary>
/// Request recovery code of a 2FA password, sent to the account mailbox.
/// See <a href="https://corefork.telegram.org/method/auth.requestPasswordRecovery" />
///</summary>
internal sealed class RequestPasswordRecoveryHandler(
    IQueryProcessor queryProcessor,
    IVerificationCodeGenerator verificationCodeGenerator,
    NandagramEmailGateway emailGateway)
    : RpcResultObjectHandler<MyTelegram.Schema.Auth.RequestRequestPasswordRecovery, MyTelegram.Schema.Auth.IPasswordRecovery>,
        Auth.IRequestPasswordRecoveryHandler
{
    protected override async Task<MyTelegram.Schema.Auth.IPasswordRecovery> HandleCoreAsync(IRequestInput input,
        MyTelegram.Schema.Auth.RequestRequestPasswordRecovery obj)
    {
        var userId = ResolveUserId(input);
        var email = await emailGateway.ResolveRecoveryMailboxAsync(queryProcessor, userId);
        if (email == null)
        {
            RpcErrors.RpcErrors400.PasswordRecoveryNa.ThrowRpcError();
            return null!;
        }

        var code = verificationCodeGenerator.Generate();
        var status = emailGateway.IssueRecoveryCode(userId, code);
        NandagramEmailGateway.ThrowIfRecoveryFailed(status);
        await emailGateway.DeliverAsync(email, EmailLetterKind.ResetPassword, code);
        return new TPasswordRecovery
        {
            EmailPattern = EmailAddressRules.Mask(email)
        };
    }

    private long ResolveUserId(IRequestInput input)
    {
        if (input.UserId != 0)
        {
            return input.UserId;
        }

        if (emailGateway.TryGetSessionUser(input.PermAuthKeyId, out var userId))
        {
            return userId;
        }

        RpcErrors.RpcErrors400.PasswordRecoveryNa.ThrowRpcError();
        return 0;
    }
}
