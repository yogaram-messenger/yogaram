// ReSharper disable All

namespace MyTelegram.Messenger.Handlers.LatestLayer.Impl.Account;

///<summary>
/// Verify an email address.
/// See <a href="https://corefork.telegram.org/method/account.verifyEmail" />
///</summary>
internal sealed class VerifyEmailHandler(NandagramEmailGateway emailGateway)
    : RpcResultObjectHandler<MyTelegram.Schema.Account.RequestVerifyEmail, MyTelegram.Schema.Account.IEmailVerified>,
        Account.IVerifyEmailHandler
{
    protected override Task<MyTelegram.Schema.Account.IEmailVerified> HandleCoreAsync(IRequestInput input,
        MyTelegram.Schema.Account.RequestVerifyEmail obj)
    {
        if (obj.Purpose is not TEmailVerifyPurposeLoginSetup setup)
        {
            RpcErrors.RpcErrors400.EmailInvalid.ThrowRpcError();
            return Task.FromResult<IEmailVerified>(null!);
        }

        if (obj.Verification is not TEmailVerificationCode verification || string.IsNullOrWhiteSpace(verification.Code))
        {
            RpcErrors.RpcErrors400.CodeEmpty.ThrowRpcError();
            return Task.FromResult<IEmailVerified>(null!);
        }

        var phone = setup.PhoneNumber.ToPhoneNumber();
        var status = emailGateway.ConfirmLoginCode(phone, setup.PhoneCodeHash, verification.Code);
        NandagramEmailGateway.ThrowIfLoginFailed(status);
        if (!emailGateway.TryGetConfirmedEmail(phone, out var email))
        {
            RpcErrors.RpcErrors400.EmailInvalid.ThrowRpcError();
            return Task.FromResult<IEmailVerified>(null!);
        }

        var sentCode = new TSentCode
        {
            Type = new TSentCodeTypeEmailCode
            {
                EmailPattern = EmailAddressRules.Mask(email),
                Length = verification.Code.Length
            },
            PhoneCodeHash = setup.PhoneCodeHash,
            Timeout = emailGateway.ResendTimeoutSeconds
        };
        IEmailVerified result = new TEmailVerifiedLogin
        {
            Email = email,
            SentCode = sentCode
        };
        return Task.FromResult(result);
    }
}
