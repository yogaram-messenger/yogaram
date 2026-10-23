namespace MyTelegram.Domain.CommandHandlers.AppCode;

public class SendAppCodeCommandHandler : CommandHandler<AppCodeAggregate, AppCodeId, SendAppCodeCommand>
{
    public override Task ExecuteAsync(AppCodeAggregate aggregate,
        SendAppCodeCommand command,
        CancellationToken cancellationToken)
    {
        if (aggregate.IsNew)
        {
            aggregate.Create(command.RequestInfo,
                command.UserId,
                command.PhoneNumber,
                command.Code,
                command.PhoneCodeHash,
                command.CreationTime);
        }
        else
        {
            aggregate.Refresh(command.RequestInfo,
                command.UserId,
                command.PhoneNumber,
                command.Code,
                command.PhoneCodeHash,
                command.CreationTime);
        }

        return Task.CompletedTask;
    }
}
