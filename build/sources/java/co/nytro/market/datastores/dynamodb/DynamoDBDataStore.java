package co.nytro.market.datastores.dynamodb;

import co.nytro.market.Market;
import co.nytro.market.config.MarketConfig;
import co.nytro.market.config.Texts;
import co.nytro.market.datastores.Listing;
import co.nytro.market.datastores.interfaces.MarketDataStore;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsyncClientBuilder;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapper;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBScanExpression;
import com.amazonaws.services.dynamodbv2.datamodeling.PaginatedScanList;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.model.*;
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

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

public class DynamoDBDataStore implements MarketDataStore {

    private final Market market = Market.instance;
    private AmazonDynamoDBAsync client;
    private final DynamoDB dynamoDB;
    private final DynamoDBMapper mapper;

    public DynamoDBDataStore(MarketConfig.DynamoDataStoreConfig config) {
        AmazonDynamoDBAsyncClientBuilder builder = AmazonDynamoDBAsyncClientBuilder.standard();
        builder.setCredentials(new DefaultAWSCredentialsProviderChain());
        builder.setRegion(config.region);
        client = builder.build();
        dynamoDB = new DynamoDB(client);
        setupTables();
        mapper = new DynamoDBMapper(client);
    }

    private void setupTables() {
        CreateTableRequest uuidCTR = new CreateTableRequest().withTableName("uuidcache").withKeySchema(new KeySchemaElement("UUID", KeyType.HASH));
        dynamoDB.createTable(uuidCTR);
        CreateTableRequest listingsCTR = new CreateTableRequest().withTableName("marketlistings");
        dynamoDB.createTable(listingsCTR);
    }

    @Override
    public void updateUUIDCache(String uuid, String name) {
        try {
            Item item = new Item().withPrimaryKey("UUID", uuid).withString("Name", name);
            dynamoDB.getTable("uuidcache").putItem(item);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void subscribe() {
        market.getLogger().info("Pub/Sub sync not yet implemented.");
    }

    @Override
    public int addListing(Player player, ItemStack itemStack, int quantityPerSale, int price) {
        try {
            if (itemStack.getQuantity() < quantityPerSale || quantityPerSale <= 0 || isBlacklisted(itemStack)) return 0;
            if (checkForOtherListings(itemStack, player.getUniqueId().toString())) return -1;

            return storeListing(itemStack, quantityPerSale, price, player.getUniqueId());
        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        }
    }

    @Override
    public int addListing(ItemStack itemStack, int quantityPerSale, int price) {
        try {
            if (itemStack.getQuantity() < quantityPerSale || quantityPerSale <= 0 || isBlacklisted(itemStack)) return 0;
            if (checkForOtherListings(itemStack, Market.SERVER_UUID)) return -1;

            return storeListing(itemStack, quantityPerSale, price, null);
        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        }
    }

    private int storeListing(ItemStack itemStack, int quantityPerSale, int price, UUID uuid) {
        String playerId = uuid != null ? uuid.toString() : Market.SERVER_UUID;
        boolean server = uuid == null;
        DynamoDBListing listing = new DynamoDBListing();
        listing.setItemStack(market.serializeItem(itemStack));
        listing.setSeller(playerId);
        listing.setPrice(price);
        listing.setQuantity(quantityPerSale);
        listing.setStock(server ? Integer.MAX_VALUE : itemStack.getQuantity());
        listing.setServerListing(true);
        mapper.save(listing);


        market.getLogger().info("listing id: " + listing.getID());

        return 0;
    }

    @Override
    public boolean checkForOtherListings(ItemStack itemStack, String s) {
        List<Listing> filtered = getListings().stream()
                .filter(listing -> listing.getSeller().equals(UUID.fromString(s)))
                .filter(listing -> market.matchItemStacks(listing.getItemStack(), itemStack)).collect(Collectors.toList());
        return filtered.size() != 0;
    }

    @Override
    public List<Listing> getListings() {
        PaginatedScanList<DynamoDBListing> scan = mapper.scan(DynamoDBListing.class, new DynamoDBScanExpression());
        List<Listing> listings = new ArrayList<>();
        scan.forEach(dbList -> listings.add(new Listing(dbList, getNameFromUUIDCache(dbList.getSeller()))));
        return listings;
    }

    @Override
    public PaginationList getListingsPagination() {
        List<Text> texts = new ArrayList<>();
        getListings().forEach(listing -> texts.add(listing.getListingsText()));
        return market.getPaginationService().builder().contents(texts).title(Texts.MARKET_LISTINGS).build();
    }

    @Override
    public List<ItemStack> removeListing(String id, String uuid, boolean staff) {
        DynamoDBScanExpression dbse = new DynamoDBScanExpression().withFilterExpression("ID = :id").withExpressionAttributeValues(Collections.singletonMap(":id", new AttributeValue().withS(id)));
        PaginatedScanList<DynamoDBListing> listing = mapper.scan(DynamoDBListing.class, dbse);
        DynamoDBListing l = listing.get(0);

        if (!l.getSeller().equals(uuid) && !staff) return null;

        Listing ll = new Listing(l, getNameFromUUIDCache(l.getSeller()));
        int inStock = ll.getStock();
        ItemStack listingIS = ll.getItemStack();
        int stacksInStock = inStock / listingIS.getMaxStackQuantity();
        List<ItemStack> stacks = new ArrayList<>();

        for (int i = 0; i < stacksInStock; i++) {
            stacks.add(listingIS.copy());
        }

        if (inStock % listingIS.getMaxStackQuantity() != 0) {
            ItemStack extra = listingIS.copy();
            extra.setQuantity(inStock % listingIS.getMaxStackQuantity());
            stacks.add(extra);
        }

        mapper.delete(l);
        return stacks;
    }

    @Override
    public PaginationList getListing(String id) {
        DynamoDBScanExpression dbse = new DynamoDBScanExpression().withFilterExpression("ID = :id").withExpressionAttributeValues(Collections.singletonMap(":id", new AttributeValue().withS(id)));
        PaginatedScanList<DynamoDBListing> listing = mapper.scan(DynamoDBListing.class, dbse);
        List<Text> texts = new ArrayList<>();
        listing.get(0).getValues().forEach((key, value) -> {
            if (!market.deserializeItemStack(value).isPresent()) return;
            switch (key) {
                case "Item":
                    texts.add(Texts.quickItemFormat(market.deserializeItemStack(value).get()));
                    break;
                case "Seller":
                    texts.add(Text.of("Seller: " + getNameFromUUIDCache(value)));
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

        return market.getPaginationService().builder().title(Texts.MARKET_LISTING.apply(Collections.singletonMap("id", id)).build()).contents(texts).build();
    }

    @Override
    public boolean addStock(ItemStack itemStack, String id, UUID uuid) {
        DynamoDBScanExpression dbse = new DynamoDBScanExpression().withFilterExpression("ID = :id").withExpressionAttributeValues(Collections.singletonMap(":id", new AttributeValue().withS(id)));
        DynamoDBListing listing = mapper.scan(DynamoDBListing.class, dbse).get(0);
        if (listing == null) return false;
        else if (!listing.getSeller().equals(uuid.toString())) return false;
        else {
            Optional<ItemStack> listingStack = market.deserializeItemStack(listing.getItemStack());
            if (!listingStack.isPresent()) return false;
            if (market.matchItemStacks(listingStack.get(), itemStack)) {
                int stock = listing.getStock();
                int quan = itemStack.getQuantity() + stock;
                listing.setStock(quan);
                mapper.save(listing);
                return true;
            } else return false;
        }
    }

    @Override
    public ItemStack purchase(UniqueAccount uniqueAccount, String id) {
        DynamoDBScanExpression dbse = new DynamoDBScanExpression().withFilterExpression("ID = :id").withExpressionAttributeValues(Collections.singletonMap(":id", new AttributeValue().withS(id)));
        DynamoDBListing listing = mapper.scan(DynamoDBListing.class, dbse).get(0);
        if (listing != null) {
            TransactionResult tr = uniqueAccount.transfer(market.getEconomyService().getOrCreateAccount(listing.getSeller()).get(), market.getEconomyService().getDefaultCurrency(), BigDecimal.valueOf(listing.getPrice()), market.marketCause);
            if (tr.getResult().equals(ResultType.SUCCESS)) {
                //get the itemstack
                ItemStack is = market.deserializeItemStack(listing.getItemStack()).get();
                //get the quantity per sale
                int quant = listing.getQuantity();
                //get the amount in stock
                int inStock = listing.getStock();
                //get the new quantity
                int newQuant = inStock - quant;
                //if the new quantity is less than the quantity to be sold, expire the listing
                if (newQuant < quant) {
                    mapper.delete(listing);
                } else {
                    listing.setStock(newQuant);
                    mapper.save(listing);
                }
                ItemStack nis = is.copy();
                nis.setQuantity(quant);
                return nis;
            } else {
                return null;
            }
        }
        return null;
    }

    @Override
    public boolean blacklistAddCmd(String id) {
        return false;
    }

    @Override
    public boolean blacklistRemoveCmd(String id) {
        return false;
    }

    @Override
    public void addIDToBlackList(String id) {

    }

    @Override
    public List<String> getBlacklistedItems() {
        return null;
    }

    @Override
    public boolean isBlacklisted(ItemStack itemStack) {
        return false;
    }

    @Override
    public PaginationList getBlacklistedItemList() {
        return null;
    }

    @Override
    public void rmIDFromBlackList(String message) {

    }

    @Override
    public PaginationList searchForItem(ItemType itemType) {
        return null;
    }

    @Override
    public PaginationList searchForUUID(UUID uniqueId) {
        return null;
    }

    @Override
    public void updateUUIDCache(Player player) {
        updateUUIDCache(player.getIdentifier(), player.getName());
    }

    @Override
    public void updateBlackList() {

    }

    private String getNameFromUUIDCache(String uuid) {
        Map<String, AttributeValue> val = new HashMap<>();
        val.put("UUID", new AttributeValue(uuid));
        GetItemResult gir;
        try {
            gir = client.getItemAsync(new GetItemRequest("uuidcache", val)).get();
            return gir.getItem().get("UUID").getS();
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
        }
        return null;
    }
}
