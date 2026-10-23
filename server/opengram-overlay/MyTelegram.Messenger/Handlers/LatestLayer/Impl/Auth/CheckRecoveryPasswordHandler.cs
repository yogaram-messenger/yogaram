// ReSharper disable All

namespace MyTelegram.Messenger.Handlers.LatestLayer.Impl.Auth;

///<summary>
/// Check if the 2FA recovery code is valid.
/// See <a href="https://corefork.telegram.org/method/auth.checkRecoveryPassword" />
///</summary>
internal sealed class CheckRecoveryPasswordHandler(NandagramEmailGateway emailGateway)
    : RpcResultObjectHandler<MyTelegram.Schema.Auth.RequestCheckRecoveryPassword, IBool>,
        Auth.ICheckRecoveryPasswordHandler
{
    protected override Task<IBool> HandleCoreAsync(IRequestInput input,
        MyTelegram.Schema.Auth.RequestCheckRecoveryPassword obj)
    {
        if (string.IsNullOrWhiteSpace(obj.Code))
        {
            RpcErrors.RpcErrors400.CodeEmpty.ThrowRpcError();
            return Task.FromResult<IBool>(null!);
        }

        var userId = ResolveUserId(input);
        var status = emailGateway.CheckRecoveryCode(userId, obj.Code, consume: false);
        NandagramEmailGateway.ThrowIfRecoveryFailed(status);
        return Task.FromResult<IBool>(new TBoolTrue());
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
