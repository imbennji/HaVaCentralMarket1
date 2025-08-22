package co.nytro.market.commands.subcommands;

import co.nytro.market.config.Texts;
import co.nytro.market.Market;
import org.spongepowered.api.command.CommandException;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.command.args.CommandContext;
import org.spongepowered.api.command.spec.CommandExecutor;
import org.spongepowered.api.data.type.HandTypes;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.item.inventory.ItemStack;
import org.spongepowered.api.text.Text;

import java.util.Optional;

/**
 * Created by TimeTheCat on 3/18/2017.
 */
public class AddStockCommand implements CommandExecutor {
    private final Market pl = Market.instance;

    @Override
    public CommandResult execute(CommandSource src, CommandContext args) throws CommandException {
        Player player = (Player) src;
        Optional<ItemStack> ois = player.getItemInHand(HandTypes.MAIN_HAND);
        if (ois.isPresent()) {
            Optional<String> oid = args.getOne(Text.of("id"));
            oid.ifPresent(s -> {
                boolean result = pl.getDataStore().addStock(ois.get(), s, player.getUniqueId());
                if (result) {
                    pl.getDataStore().getListing(s).sendTo(player);
                    player.setItemInHand(HandTypes.MAIN_HAND, null);
                } else player.sendMessage(Texts.COULD_NOT_ADD_STOCK);
            });
        } else throw new CommandException(Texts.AIR_ITEM);
        return CommandResult.success();
    }
}
