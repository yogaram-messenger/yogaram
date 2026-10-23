// ReSharper disable All

using MyTelegram.Domain.Aggregates.UserPassword;
using MyTelegram.Domain.Commands.UserPassword;

namespace MyTelegram.Messenger.Handlers.LatestLayer.Impl.Auth;

///<summary>
/// Reset the 2FA password using the recovery code sent to the bound mailbox.
/// See <a href="https://corefork.telegram.org/method/auth.recoverPassword" />
///</summary>
internal sealed class RecoverPasswordHandler(
    IQueryProcessor queryProcessor,
    ICommandBus commandBus,
    IEventBus eventBus,
    IUserConverterService userConverterService,
    ILayeredService<IAuthorizationConverter> authorizationLayeredService,
    IPhotoAppService photoAppService,
    NandagramEmailGateway emailGateway)
    : RpcResultObjectHandler<MyTelegram.Schema.Auth.RequestRecoverPassword, MyTelegram.Schema.Auth.IAuthorization>,
        Auth.IRecoverPasswordHandler
{
    protected override async Task<MyTelegram.Schema.Auth.IAuthorization> HandleCoreAsync(IRequestInput input,
        MyTelegram.Schema.Auth.RequestRecoverPassword obj)
    {
        if (string.IsNullOrWhiteSpace(obj.Code))
        {
            RpcErrors.RpcErrors400.CodeEmpty.ThrowRpcError();
            return null!;
        }

        var userId = ResolveUserId(input);
        var status = emailGateway.CheckRecoveryCode(userId, obj.Code, consume: true);
        NandagramEmailGateway.ThrowIfRecoveryFailed(status);

        if (obj.NewSettings is not TPasswordInputSettings settings
            || settings.NewAlgo is not TPasswordKdfAlgoSHA256SHA256PBKDF2HMACSHA512iter100000SHA256ModPow algo
            || settings.NewPasswordHash == null
            || settings.NewPasswordHash.Length == 0)
        {
            RpcErrors.RpcErrors400.NewSettingsInvalid.ThrowRpcError();
            return null!;
        }

        var recoveryEmail = await RecoveryEmailAsync(userId, settings.Email);
        var setCommand = new SetPasswordCommand(
            UserPasswordId.Create(userId),
            input.ToRequestInfo() with { UserId = userId },
            userId,
            algo.Salt1.ToArray(),
            algo.Salt2.ToArray(),
            settings.NewPasswordHash,
            settings.Hint,
            recoveryEmail,
            algo.G,
            algo.P.ToArray());
        await commandBus.PublishAsync(setCommand);

        await eventBus.PublishAsync(new UserSignInSuccessEvent(
            input.ReqMsgId,
            input.AuthKeyId,
            input.PermAuthKeyId,
            userId,
            PasswordState.None));

        var userReadModel = await queryProcessor.ProcessAsync(new GetUserByIdQuery(userId));
        if (userReadModel == null)
        {
            RpcErrors.RpcErrors400.UserIdInvalid.ThrowRpcError();
            return null!;
        }

        var photos = await photoAppService.GetPhotosAsync(userReadModel);
        var user = userConverterService.ToUser(input, userReadModel, photos, layer: input.Layer);
        return authorizationLayeredService.GetConverter(input.Layer).CreateAuthorization(user);
    }

    private async Task<string> RecoveryEmailAsync(long userId, string? requested)
    {
        if (EmailAddressRules.TryNormalize(requested, out var email))
        {
            emailGateway.RememberUserEmail(userId, email);
            return email;
        }

        var bound = await emailGateway.ResolveRecoveryMailboxAsync(queryProcessor, userId);
        if (bound == null)
        {
            RpcErrors.RpcErrors400.PasswordRecoveryNa.ThrowRpcError();
            return string.Empty;
        }

        return bound;
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
