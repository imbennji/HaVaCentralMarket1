package co.nytro.market.datastores;

import com.codehusky.huskyui.StateContainer;
import com.codehusky.huskyui.states.Page;
import co.nytro.market.config.Texts;
import com.codehusky.huskyui.states.action.ActionType;
import com.codehusky.huskyui.states.action.CommandAction;
import com.codehusky.huskyui.states.action.runnable.RunnableAction;
import com.codehusky.huskyui.states.element.ActionableElement;
import org.spongepowered.api.data.key.Keys;
import org.spongepowered.api.data.type.DyeColors;
import org.spongepowered.api.item.ItemTypes;
import org.spongepowered.api.item.inventory.ItemStack;
import org.spongepowered.api.item.inventory.property.InventoryDimension;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.format.TextColors;

import java.util.ArrayList;
import java.util.List;

public class UIBuilder {

    public static StateContainer getStateContainer(List<Listing> listings) {
        StateContainer sc = new StateContainer();

        final int pageSize = 9 * 5; // Reserve bottom row for controls
        final int bottomRowStart = pageSize; // slot 45 in a 9x6 grid
        final int bottomRowEnd = bottomRowStart + 8; // slot 53
        int totalPages = (int) Math.ceil(listings.size() / (double) pageSize);

        for (int page = 0; page < totalPages; page++) {
            int start = page * pageSize;
            int end = Math.min(start + pageSize, listings.size());

            Page.PageBuilder p = Page.builder()
                    .setTitle(Texts.MARKET_BASE)
                    .setInventoryDimension(InventoryDimension.of(9, 6))
                    .setEmptyStack(ItemStack.builder()
                            .itemType(ItemTypes.STAINED_GLASS_PANE)
                            .add(Keys.DYE_COLOR, DyeColors.GREEN)
                            .add(Keys.DISPLAY_NAME, Text.of(""))
                            .build());

            int slot = 0;
            for (Listing listing : listings.subList(start, end)) {
                p.addElement(getElementFromListing(listing, sc), slot++);
            }

            String id = String.format("Market Page %d", page + 1);

            // Fill bottom control row with filler panes
            ItemStack fillerStack = ItemStack.builder()
                    .itemType(ItemTypes.STAINED_GLASS_PANE)
                    .add(Keys.DYE_COLOR, DyeColors.GREEN)
                    .add(Keys.DISPLAY_NAME, Text.of(""))
                    .build();
            ActionableElement fillerElement = new ActionableElement(
                    new RunnableAction(sc, ActionType.CLOSE, id, player -> {}), fillerStack);
            for (int i = bottomRowStart; i <= bottomRowEnd; i++) {
                p.addElement(fillerElement, i);
            }

            // Previous page button
            if (page > 0) {
                ItemStack prevStack = ItemStack.builder()
                        .itemType(ItemTypes.ARROW)
                        .add(Keys.DISPLAY_NAME, Text.of("Prev"))
                        .build();
                RunnableAction prevAction = new RunnableAction(sc, ActionType.CLOSE, id,
                        player -> sc.launchFor(player, String.format("Market Page %d", page)));
                p.addElement(new ActionableElement(prevAction, prevStack), bottomRowStart);
            }

            // Next page button
            if (page < totalPages - 1) {
                ItemStack nextStack = ItemStack.builder()
                        .itemType(ItemTypes.ARROW)
                        .add(Keys.DISPLAY_NAME, Text.of("Next"))
                        .build();
                RunnableAction nextAction = new RunnableAction(sc, ActionType.CLOSE, id,
                        player -> sc.launchFor(player, String.format("Market Page %d", page + 2)));
                p.addElement(new ActionableElement(nextAction, nextStack), bottomRowEnd);
            }

            sc.addState(p.build(id));
        }

        return sc;
    }

    private static ActionableElement getElementFromListing(Listing listing, StateContainer sc) {
        ItemStack i = listing.getItemStack().copy();
        i.setQuantity(listing.getQuantity());
        List<Text> lore = new ArrayList<>();
        lore.add(Texts.guiListing.apply(listing.getSource()).build());
        lore.add(Text.builder().color(TextColors.WHITE).append(Text.of("Seller: " + listing.getSellerName())).build());
        i.offer(Keys.ITEM_LORE, lore);
        CommandAction ca = new CommandAction(sc, ActionType.CLOSE, "Main Market", "market check " + listing.getId(), CommandAction.CommandReceiver.PLAYER);
        return new ActionableElement(ca, i);
    }
}
