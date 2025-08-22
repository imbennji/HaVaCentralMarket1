package co.nytro.market.commands.subcommands;

import co.nytro.market.datastores.UIBuilder;
import co.nytro.market.Market;
import com.codehusky.huskyui.StateContainer;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.format.TextColors;
import java.util.Optional;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.command.args.CommandContext;
import org.spongepowered.api.command.spec.CommandExecutor;
import org.spongepowered.api.entity.living.player.Player;

/**
 * Created by TimeTheCat on 3/18/2017.
 */
public class ListingsCommand implements CommandExecutor {
    private final Market pl = Market.instance;

    @Override
    public CommandResult execute(CommandSource src, CommandContext args) {
        if (pl.isHuskyUILoaded() && pl.isChestGUIDefault()) {
            if (args.hasAny("g")) {
                pl.getDataStore().getListingsPagination().sendTo(src);
            } else {
                Optional<StateContainer> scOpt = UIBuilder.getStateContainer(pl.getDataStore().getListings());
                if (scOpt.isPresent()) {
                    scOpt.get().launchFor((Player) src, "Market Page 1");
                } else {
                    src.sendMessage(Text.of(TextColors.RED, "No listings available."));
                }
            }
        } else {
            pl.getDataStore().getListingsPagination().sendTo(src);
        }

        return CommandResult.success();
    }
}
