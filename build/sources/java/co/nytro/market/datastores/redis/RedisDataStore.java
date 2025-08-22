package co.nytro.market.datastores.redis;

import co.nytro.market.Market;
import co.nytro.market.config.MarketConfig;
import co.nytro.market.config.Texts;
import co.nytro.market.datastores.Listing;
import co.nytro.market.datastores.interfaces.MarketDataStore;
import com.google.common.collect.Lists;
import org.spongepowered.api.block.BlockType;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.item.ItemType;
import org.spongepowered.api.item.inventory.ItemStack;
import org.spongepowered.api.service.economy.account.UniqueAccount;
import org.spongepowered.api.service.economy.transaction.ResultType;
import org.spongepowered.api.service.economy.transaction.TransactionResult;
import org.spongepowered.api.service.pagination.PaginationList;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.action.TextActions;
import org.spongepowered.api.text.format.TextColors;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Transaction;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

public class RedisDataStore implements MarketDataStore {

    private final Market plugin = Market.instance;
    private final JedisPool jedisPool;
    private final String redisPass;
    private final String redisHost;
    private final int redisPort;
    private RedisPubSub sub;
    private List<String> blacklistedItems;

    public RedisDataStore(MarketConfig.RedisDataStoreConfig config) {
        this.redisPort = config.port;
        this.redisPass = config.password;
        this.redisHost = config.host;
        this.jedisPool = setupRedis();
        subscribe();
        updateBlackList();
        setServerUUID();
    }

    private void setServerUUID() {
        updateUUIDCache(Market.SERVER_UUID, "Server");
    }

    private JedisPool setupRedis() {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(128);
        if (!redisPass.equals("")) {
            return new JedisPool(config, this.redisHost, this.redisPort, 0, this.redisPass);
        } else {
            return new JedisPool(config, this.redisHost, this.redisPort, 0);
        }
    }

    private JedisPool getJedis() {
        if (jedisPool == null) {
            if (this.redisPass != null) {
                return setupRedis();
            } else {
                return setupRedis();
            }
        } else {
            return jedisPool;
        }
    }

    @Override
    public void updateUUIDCache(String uuid, String name) {
        try (Jedis jedis = getJedis().getResource()) {
            jedis.hset(RedisKeys.UUID_CACHE, uuid, name);
        }
    }

    @Override
    public void subscribe() {
        plugin.getScheduler().createTaskBuilder()
                .async()
                .execute(() -> {
                    sub = new RedisPubSub(this);
                    try (Jedis jedis = getJedis().getResource()) {
                        jedis.subscribe(sub, RedisPubSub.Channels.channels);
                    }
                });
    }

    @Override
    public int addListing(Player player, ItemStack itemStack, int quantityPerSale, int price) {
        try (Jedis jedis = getJedis().getResource()) {
            //if there are fewer items than they want to sell every time, return 0
            if (itemStack.getQuantity() < quantityPerSale || quantityPerSale <= 0 || isBlacklisted(itemStack)) return 0;
            // if no listings have been created
            if (!jedis.exists(RedisKeys.LAST_MARKET_ID)) {
                // set the last market id to 1
                jedis.set(RedisKeys.LAST_MARKET_ID, String.valueOf(1));
                int id = 1;
                // create key to store listing
                String key = RedisKeys.MARKET_ITEM_KEY(String.valueOf(id));
                // store and update db
                return execTransaction(itemStack, quantityPerSale, player.getUniqueId(), price, jedis, id, key);
            } else {
                // otherwise
                // check if the user has any other listings for the same item.
                if (checkForOtherListings(itemStack, player.getUniqueId().toString())) return -1;
                // get the next listing id
                int id = Integer.parseInt(jedis.get(RedisKeys.LAST_MARKET_ID));
                // create key to store listing
                String key = RedisKeys.MARKET_ITEM_KEY(String.valueOf(id));
                // if not, store and update db
                return execTransaction(itemStack, quantityPerSale, player.getUniqueId(), price, jedis, id, key);
            }
        }
    }

    @Override
    public int addListing(ItemStack itemStack, int quantityPerSale, int price) {
        try (Jedis jedis = getJedis().getResource()) {
            //if there are fewer items than they want to sell every time, return 0
            if (itemStack.getQuantity() < quantityPerSale || quantityPerSale <= 0 || isBlacklisted(itemStack)) return 0;
            // if no market listings exist yet
            // if no listings have been created
            if (!jedis.exists(RedisKeys.LAST_MARKET_ID)) {
                // set the last market id to 1
                jedis.set(RedisKeys.LAST_MARKET_ID, String.valueOf(1));
                int id = 1;
                // create key to store listing
                String key = RedisKeys.MARKET_ITEM_KEY(String.valueOf(id));
                // store and update db
                return execTransaction(itemStack, quantityPerSale, null, price, jedis, id, key);
            } else {
                // otherwise
                // make sure the user has no other listings for the same item
                if (checkForOtherListings(itemStack, Market.SERVER_UUID)) return -1;
                // get the next listing id
                int id = Integer.parseInt(jedis.get(RedisKeys.LAST_MARKET_ID));
                // create key to store listing
                String key = RedisKeys.MARKET_ITEM_KEY(String.valueOf(id));
                // store and update db
                return execTransaction(itemStack, quantityPerSale, null, price, jedis, id, key);
            }
        }
    }

    private int execTransaction(ItemStack itemStack, int quantityPerSale, UUID uuid, int price, Jedis jedis, int id, String key) {
        // if the uuid is null, it's a server listing
        String playerId = uuid != null ? uuid.toString() : Market.SERVER_UUID;

        // create transaction
        Transaction m = jedis.multi();
        boolean server = uuid == null;
        m.hset(key, "Item", plugin.serializeItem(itemStack));
        m.hset(key, "Seller", playerId);
        m.hset(key, "Stock", String.valueOf(server ? Integer.MAX_VALUE : itemStack.getQuantity()));
        m.hset(key, "Price", String.valueOf(price));
        m.hset(key, "Quantity", String.valueOf(quantityPerSale));
        m.hset(key, "Server", String.valueOf(server));
        m.exec();

        jedis.hset(RedisKeys.FOR_SALE, String.valueOf(id), playerId);

        jedis.incr(RedisKeys.LAST_MARKET_ID);

        return id;
    }

    @Override
    public boolean checkForOtherListings(ItemStack itemStack, String s) {
        try (Jedis jedis = getJedis().getResource()) {
            // get listings
            Map<String, String> d = jedis.hgetAll(RedisKeys.FOR_SALE);

            Map<String, String> e = d.entrySet().stream().filter(stringStringEntry -> stringStringEntry.getValue().equals(s)).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            if (e.size() == 0) return false;
            else {
                final boolean[] hasOther = {false};
                e.forEach((s1, s2) -> {
                    try {
                        Optional<ItemStack> ooi = plugin.deserializeItemStack(jedis.hget(RedisKeys.MARKET_ITEM_KEY(s1), "Item"));
                        if (!ooi.isPresent()) return;
                        if (plugin.matchItemStacks(ooi.get(), itemStack)) {
                            hasOther[0] = true;
                        }
                    } catch (Exception ignored) {
                    }
                });
                return hasOther[0];
            }
        }
    }

    @Override
    public List<Listing> getListings() {
        try (Jedis jedis = getJedis().getResource()) {
            Set<String> openListings = jedis.hgetAll(RedisKeys.FOR_SALE).keySet();
            List<Listing> listings = new ArrayList<>();
            openListings.forEach(s -> {
                Map<String, String> listing = jedis.hgetAll(RedisKeys.MARKET_ITEM_KEY(s));
                Listing l = new Listing(listing, s, jedis.hget(RedisKeys.UUID_CACHE, listing.get("Seller")));
                if (l.getItemStack() == null) return;
                listings.add(l);
            });
            return listings;
        }
    }

    @Override
    public PaginationList getListingsPagination() {
        List<Text> texts = new ArrayList<>();
        getListings().forEach(listing -> texts.add(listing.getListingsText()));
        return plugin.getPaginationService().builder().contents(texts).title(Texts.MARKET_LISTINGS).build();
    }

    @Override
    public List<ItemStack> removeListing(String id, String uuid, boolean staff) {
        try (Jedis jedis = getJedis().getResource()) {
            if (!jedis.hexists(RedisKeys.FOR_SALE, id)) return null;
            else {
                //get info about the listing
                Map<String, String> listing = jedis.hgetAll(RedisKeys.MARKET_ITEM_KEY(id));
                //check to see if the uuid matches the seller, or the user is a staff member
                if (!listing.get("Seller").equals(uuid) && !staff) return null;
                if (Boolean.valueOf(listing.getOrDefault("Server", String.valueOf(false)))) {
                    jedis.hdel(RedisKeys.FOR_SALE, id);
                    return new ArrayList<>();
                }
                //get how much stock it has
                int inStock = Integer.parseInt(listing.get("Stock"));
                //deserialize the item
                ItemStack listingIS = plugin.deserializeItemStack(listing.get("Item")).get();
                //calculate the amount of stacks to make
                int stacksInStock = inStock / listingIS.getMaxStackQuantity();
                //new list for stacks
                List<ItemStack> stacks = new ArrayList<>();
                //until all stacks are pulled out, keep adding more stacks to stacks
                for (int i = 0; i < stacksInStock; i++) {
                    stacks.add(listingIS.copy());
                }
                if (inStock % listingIS.getMaxStackQuantity() != 0) {
                    ItemStack extra = listingIS.copy();
                    extra.setQuantity(inStock % listingIS.getMaxStackQuantity());
                    stacks.add(extra);
                }
                //remove from the listings
                jedis.hdel(RedisKeys.FOR_SALE, id);
                return stacks;
            }
        }
    }

    @Override
    public PaginationList getListing(String id) {
        try (Jedis jedis = getJedis().getResource()) {
            //if the item is not for sale, do not get the listing
            if (!jedis.hexists(RedisKeys.FOR_SALE, id)) return null;
            //get info about the listing
            Map<String, String> listing = jedis.hgetAll(RedisKeys.MARKET_ITEM_KEY(id));
            //create list of Texts for pages
            List<Text> texts = new ArrayList<>();
            //replace with item if key is "Item", replace uuid with name from Vote4Dis cache.
            listing.forEach((key, value) -> {
                switch (key) {
                    case "Item":
                        texts.add(Texts.quickItemFormat(plugin.deserializeItemStack(value).get()));
                        break;
                    case "Seller":
                        texts.add(Text.of("Seller: " + jedis.hget(RedisKeys.UUID_CACHE, value)));
                        break;
                    default:
                        texts.add(Text.of(key + ": " + value));
                        break;
                }
            });

            texts.add(Text.builder()
                    .append(Text.builder()
                            .color(TextColors.GREEN)
                            .append(Text.of("[Buy]"))
                            .onClick(TextActions.suggestCommand("/market buy " + id))
                            .build())
                    .append(Text.of(" "))
                    .append(Text.builder()
                            .color(TextColors.GREEN)
                            .append(Text.of("[QuickBuy]"))
                            .onClick(TextActions.runCommand("/market buy " + id))
                            .onHover(TextActions.showText(Text.of("Click here to run the command to buy the item.")))
                            .build())
                    .build());

            return plugin.getPaginationService().builder().title(Texts.MARKET_LISTING.apply(Collections.singletonMap("id", id)).build()).contents(texts).build();
        }
    }

    @Override
    public boolean addStock(ItemStack itemStack, String id, UUID uuid) {
        try (Jedis jedis = getJedis().getResource()) {
            if (!jedis.hexists(RedisKeys.FOR_SALE, id)) return false;
            else if (!jedis.hget(RedisKeys.MARKET_ITEM_KEY(id), "Seller").equals(uuid.toString())) return false;
            else {
                ItemStack listingStack = plugin.deserializeItemStack(jedis.hget(RedisKeys.MARKET_ITEM_KEY(id), "Item")).get();
                //if the stack in the listing matches the stack it's trying to add, add it to the stack
                if (plugin.matchItemStacks(listingStack, itemStack)) {
                    int stock = Integer.parseInt(jedis.hget(RedisKeys.MARKET_ITEM_KEY(id), "Stock"));
                    int quan = itemStack.getQuantity() + stock;
                    jedis.hset(RedisKeys.MARKET_ITEM_KEY(id), "Stock", String.valueOf(quan));
                    return true;
                } else return false;
            }
        }
    }

    @Override
    public ItemStack purchase(UniqueAccount uniqueAccount, String id) {
        try (Jedis jedis = getJedis().getResource()) {
            if (!jedis.hexists(RedisKeys.FOR_SALE, id)) return null;
            else {
                TransactionResult tr = uniqueAccount.transfer(plugin.getEconomyService().getOrCreateAccount(UUID.fromString(jedis.hget(RedisKeys.MARKET_ITEM_KEY(id), "Seller"))).get(), plugin.getEconomyService().getDefaultCurrency(), BigDecimal.valueOf(Long.parseLong(jedis.hget(RedisKeys.MARKET_ITEM_KEY(id), "Price"))), plugin.marketCause);
                if (tr.getResult().equals(ResultType.SUCCESS)) {
                    //get the itemstack
                    ItemStack is = plugin.deserializeItemStack(jedis.hget(RedisKeys.MARKET_ITEM_KEY(id), "Item")).get();
                    //get the quantity per sale
                    int quant = Integer.parseInt(jedis.hget(RedisKeys.MARKET_ITEM_KEY(id), "Quantity"));
                    //get the amount in stock
                    int inStock = Integer.parseInt(jedis.hget(RedisKeys.MARKET_ITEM_KEY(id), "Stock"));
                    //get the new quantity
                    int newQuant = inStock - quant;
                    //if the new quantity is less than the quantity to be sold, expire the listing
                    if (newQuant < quant) {
                        jedis.hdel(RedisKeys.FOR_SALE, id);
                    } else {
                        jedis.hset(RedisKeys.MARKET_ITEM_KEY(id), "Stock", String.valueOf(newQuant));
                    }
                    ItemStack nis = is.copy();
                    nis.setQuantity(quant);
                    return nis;
                } else {
                    return null;
                }
            }
        }
    }

    @Override
    public boolean blacklistAddCmd(String id) {
        try (Jedis jedis = getJedis().getResource()) {
            if (jedis.hexists(RedisKeys.BLACKLIST, id)) return false;
            else
                jedis.hset(RedisKeys.BLACKLIST, id, String.valueOf(true));
            jedis.publish(RedisPubSub.Channels.marketBlacklistAdd, id);
            return true;
        }
    }

    @Override
    public boolean blacklistRemoveCmd(String id) {
        try (Jedis jedis = getJedis().getResource()) {
            if (!jedis.hexists(RedisKeys.BLACKLIST, id)) return false;
            else
                jedis.hdel(RedisKeys.BLACKLIST, id);
            jedis.publish(RedisPubSub.Channels.marketBlacklistRemove, id);
            return true;
        }
    }

    @Override
    public void addIDToBlackList(String id) {
        blacklistedItems.add(id);
    }

    @Override
    public List<String> getBlacklistedItems() {
        return blacklistedItems;
    }

    @Override
    public boolean isBlacklisted(ItemStack itemStack) {
        Optional<BlockType> type = itemStack.getType().getBlock();
        String id = type.map(blockType -> blockType.getDefaultState().getId()).orElseGet(() -> itemStack.getType().getId());
        return blacklistedItems.contains(id);
    }

    @Override
    public PaginationList getBlacklistedItemList() {
        List<Text> texts = new ArrayList<>();
        for (String blacklistedItem : getBlacklistedItems()) {
            texts.add(Text.of(blacklistedItem));
        }
        return plugin.getPaginationService().builder().contents(texts).title(Text.of(TextColors.GREEN, "Market Blacklist")).build();
    }

    @Override
    public void rmIDFromBlackList(String message) {
        blacklistedItems.remove(message);
    }

    @Override
    public PaginationList searchForItem(ItemType itemType) {
        try (Jedis jedis = getJedis().getResource()) {
            Set<String> openListings = jedis.hgetAll(RedisKeys.FOR_SALE).keySet();
            List<Text> texts = new ArrayList<>();
            for (String openListing : openListings) {
                Map<String, String> listing = jedis.hgetAll(RedisKeys.MARKET_ITEM_KEY(openListing));
                Text.Builder l = Text.builder();
                Optional<ItemStack> is = plugin.deserializeItemStack(listing.get("Item"));
                if (!is.isPresent()) continue;
                if (is.get().getType().equals(itemType)) {
                    l.append(Texts.quickItemFormat(is.get()));
                    l.append(Text.of(" "));
                    l.append(Text.of(TextColors.WHITE, "@"));
                    l.append(Text.of(" "));
                    l.append(Text.of(TextColors.GREEN, "$" + listing.get("Price")));
                    l.append(Text.of(" "));
                    l.append(Text.of(TextColors.WHITE, "for"));
                    l.append(Text.of(" "));
                    l.append(Text.of(TextColors.GREEN, listing.get("Quantity") + "x"));
                    l.append(Text.of(" "));
                    l.append(Text.of(TextColors.WHITE, "Seller:"));
                    l.append(Text.of(TextColors.LIGHT_PURPLE, " " + jedis.hget(RedisKeys.UUID_CACHE, listing.get("Seller"))));
                    l.append(Text.of(" "));
                    l.append(Text.builder()
                            .color(TextColors.GREEN)
                            .onClick(TextActions.runCommand("/market check " + openListing))
                            .append(Text.of("[Info]"))
                            .onHover(TextActions.showText(Text.of("View more info about this listing.")))
                            .build());
                    texts.add(l.build());
                }
            }
            if (texts.size() == 0) texts.add(Text.of(TextColors.RED, "No listings found."));
            return plugin.getPaginationService().builder().contents(texts).title(Texts.MARKET_SEARCH).build();
        }
    }

    @Override
    public PaginationList searchForUUID(UUID uniqueId) {
        try (Jedis jedis = getJedis().getResource()) {
            Set<String> openListings = jedis.hgetAll(RedisKeys.FOR_SALE).keySet();
            List<Text> texts = new ArrayList<>();
            for (String openListing : openListings) {
                Map<String, String> listing = jedis.hgetAll(RedisKeys.MARKET_ITEM_KEY(openListing));
                if (listing.get("Seller").equals(uniqueId.toString())) {
                    Text.Builder l = Text.builder();
                    Optional<ItemStack> is = plugin.deserializeItemStack(listing.get("Item"));
                    if (!is.isPresent()) continue;
                    l.append(Texts.quickItemFormat(is.get()));
                    l.append(Text.of(" "));
                    l.append(Text.of(TextColors.WHITE, "@"));
                    l.append(Text.of(" "));
                    l.append(Text.of(TextColors.GREEN, "$" + listing.get("Price")));
                    l.append(Text.of(" "));
                    l.append(Text.of(TextColors.WHITE, "for"));
                    l.append(Text.of(" "));
                    l.append(Text.of(TextColors.GREEN, listing.get("Quantity") + "x"));
                    l.append(Text.of(" "));
                    l.append(Text.of(TextColors.WHITE, "Seller:"));
                    l.append(Text.of(TextColors.LIGHT_PURPLE, " " + jedis.hget(RedisKeys.UUID_CACHE, listing.get("Seller"))));
                    l.append(Text.of(" "));
                    l.append(Text.builder()
                            .color(TextColors.GREEN)
                            .onClick(TextActions.runCommand("/market check " + openListing))
                            .append(Text.of("[Info]"))
                            .onHover(TextActions.showText(Text.of("View more info about this listing.")))
                            .build());
                    texts.add(l.build());
                }
            }
            if (texts.size() == 0) texts.add(Text.of(TextColors.RED, "No listings found."));
            return plugin.getPaginationService().builder().contents(texts).title(Texts.MARKET_SEARCH).build();
        }
    }

    @Override
    public void updateUUIDCache(Player player) {
        updateUUIDCache(player.getUniqueId().toString(), player.getName());
    }

    @Override
    public void updateBlackList() {
        try (Jedis jedis = getJedis().getResource()) {
            blacklistedItems = Lists.newArrayList(jedis.hgetAll(RedisKeys.BLACKLIST).keySet());
        }
    }
}
