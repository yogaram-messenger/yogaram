// ReSharper disable All

namespace MyTelegram.Messenger.Handlers.LatestLayer.Impl.Account;

///<summary>
/// Send an email verification code.
/// See <a href="https://corefork.telegram.org/method/account.sendVerifyEmailCode" />
///</summary>
internal sealed class SendVerifyEmailCodeHandler(
    ICommandBus commandBus,
    IVerificationCodeGenerator verificationCodeGenerator,
    NandagramEmailGateway emailGateway)
    : RpcResultObjectHandler<MyTelegram.Schema.Account.RequestSendVerifyEmailCode, MyTelegram.Schema.Account.ISentEmailCode>,
        Account.ISendVerifyEmailCodeHandler
{
    protected override async Task<MyTelegram.Schema.Account.ISentEmailCode> HandleCoreAsync(IRequestInput input,
        MyTelegram.Schema.Account.RequestSendVerifyEmailCode obj)
    {
        if (obj.Purpose is not TEmailVerifyPurposeLoginSetup setup)
        {
            RpcErrors.RpcErrors400.EmailInvalid.ThrowRpcError();
            return null!;
        }

        if (!EmailAddressRules.TryNormalize(obj.Email, out var email))
        {
            RpcErrors.RpcErrors400.EmailInvalid.ThrowRpcError();
            return null!;
        }

        var phone = setup.PhoneNumber.ToPhoneNumber();
        var code = verificationCodeGenerator.Generate();
        if (emailGateway.IsLoginConfirmed(phone, setup.PhoneCodeHash))
        {
            if (!emailGateway.TryGetConfirmedEmail(phone, out var bound) || !string.Equals(bound, email, StringComparison.Ordinal))
            {
                RpcErrors.RpcErrors400.EmailNotAllowed.ThrowRpcError();
                return null!;
            }

            var userId = emailGateway.RegistrationUserId(phone, setup.PhoneCodeHash);
            await emailGateway.StoreAppCodeAsync(commandBus, input, phone, setup.PhoneCodeHash, code, userId);
            await emailGateway.DeliverAsync(bound, EmailLetterKind.SignIn, code);
        }
        else
        {
            var status = emailGateway.IssueLoginCode(phone, setup.PhoneCodeHash, email, code);
            NandagramEmailGateway.ThrowIfLoginFailed(status);
            var userId = emailGateway.RegistrationUserId(phone, setup.PhoneCodeHash);
            await emailGateway.StoreAppCodeAsync(commandBus, input, phone, setup.PhoneCodeHash, code, userId);
            await emailGateway.DeliverAsync(email, EmailLetterKind.ConfirmRegistration, code);
        }

        return new TSentEmailCode
        {
            EmailPattern = EmailAddressRules.Mask(email),
            Length = code.Length
        };
    }
}
