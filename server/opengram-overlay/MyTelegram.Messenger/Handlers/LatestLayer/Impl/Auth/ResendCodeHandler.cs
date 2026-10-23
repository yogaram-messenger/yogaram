namespace MyTelegram.Messenger.Handlers.LatestLayer.Impl.Auth;

///<summary>
/// Resend the login code to the mailbox already bound to this phone code hash.
/// See <a href="https://corefork.telegram.org/method/auth.resendCode" />
///</summary>
internal sealed class ResendCodeHandler(
    ICommandBus commandBus,
    IOptionsMonitor<MyTelegramMessengerServerOptions> options,
    IVerificationCodeGenerator verificationCodeGenerator,
    NandagramEmailGateway emailGateway)
    : RpcResultObjectHandler<MyTelegram.Schema.Auth.RequestResendCode, MyTelegram.Schema.Auth.ISentCode>,
        Auth.IResendCodeHandler
{
    protected override async Task<ISentCode> HandleCoreAsync(IRequestInput input,
        RequestResendCode obj)
    {
        if (!options.CurrentValue.EnableEmailLogin)
        {
            RpcErrors.RpcErrors406.SendCodeUnavailable.ThrowRpcError();
            return null!;
        }

        var phone = obj.PhoneNumber.ToPhoneNumber();
        if (!emailGateway.TryGetLoginEmail(phone, obj.PhoneCodeHash, out var email, out var confirmed))
        {
            RpcErrors.RpcErrors400.PhoneCodeExpired.ThrowRpcError();
            return null!;
        }

        var code = verificationCodeGenerator.Generate();
        var userId = emailGateway.RegistrationUserId(phone, obj.PhoneCodeHash);
        if (!confirmed)
        {
            var status = emailGateway.IssueLoginCode(phone, obj.PhoneCodeHash, email, code);
            NandagramEmailGateway.ThrowIfLoginFailed(status);
            userId = emailGateway.RegistrationUserId(phone, obj.PhoneCodeHash);
        }

        await emailGateway.StoreAppCodeAsync(commandBus, input, phone, obj.PhoneCodeHash, code, userId);
        await emailGateway.DeliverAsync(email, confirmed ? EmailLetterKind.SignIn : EmailLetterKind.ConfirmRegistration, code);
        return new TSentCode
        {
            Type = new TSentCodeTypeEmailCode
            {
                EmailPattern = EmailAddressRules.Mask(email),
                Length = code.Length
            },
            PhoneCodeHash = obj.PhoneCodeHash,
            Timeout = emailGateway.ResendTimeoutSeconds
        };
    }
}
