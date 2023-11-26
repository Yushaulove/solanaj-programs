import com.google.common.io.Files;
import com.google.common.io.Resources;
import com.mmorrell.phoenix.manager.PhoenixManager;
import com.mmorrell.phoenix.model.LimitOrderPacketRecord;
import com.mmorrell.phoenix.model.PhoenixMarket;
import com.mmorrell.phoenix.model.PhoenixMarketHeader;
import com.mmorrell.phoenix.program.PhoenixProgram;
import com.mmorrell.phoenix.program.PhoenixSeatManagerProgram;
import com.mmorrell.phoenix.util.Keccak;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Base58;
import org.bitcoinj.core.Utils;
import org.junit.Test;
import org.p2p.solanaj.core.Account;
import org.p2p.solanaj.core.PublicKey;
import org.p2p.solanaj.core.Transaction;
import org.p2p.solanaj.programs.ComputeBudgetProgram;
import org.p2p.solanaj.rpc.Cluster;
import org.p2p.solanaj.rpc.RpcClient;
import org.p2p.solanaj.rpc.RpcException;
import org.p2p.solanaj.rpc.types.AccountInfo;
import org.p2p.solanaj.rpc.types.ProgramAccount;
import org.p2p.solanaj.rpc.types.config.Commitment;
import org.p2p.solanaj.ws.SubscriptionWebSocketClient;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Slf4j
public class PhoenixTest {

    private final RpcClient client = new RpcClient("https://mainnet.helius-rpc.com/?api-key=a778b653-bdd6-41bc-8cda-0c7377faf1dd");
    private static final PublicKey SOL_USDC_MARKET = new PublicKey(
            "4DoNfFBfF7UokCC2FQzriy7yHK6DY6NVdYpuekQ5pRgg"
    );
    private static final PublicKey SOL_USDC_SEAT_MANAGER = new PublicKey(
            "JB3443UaUDA3z47AYdK4AUG8pgFgLfJVyyitHYkqC17L"
    );

    @Test
    public void phoenixGetMarketsTest() throws RpcException {
        // GPA for all markets
        final List<ProgramAccount> markets = client.getApi().getProgramAccountsBase64(
                PhoenixProgram.PHOENIX_PROGRAM_ID,
                0,
                getDiscriminator("phoenix::program::accounts::MarketHeader")
        );

        System.out.println("Number of markets: " + markets.size());
        markets.forEach(programAccount -> {
            System.out.println("Market: " + programAccount.getPubkey());

            final PhoenixMarketHeader phoenixMarketHeader = PhoenixMarketHeader.readPhoenixMarketHeader(
                    Arrays.copyOfRange(
                            programAccount.getAccount().getDecodedData(),
                            0,
                            PhoenixMarketHeader.MARKET_HEADER_SIZE
                    )
            );
            System.out.println(phoenixMarketHeader);

        });
    }

    @Test
    public void phoenixManagerTest() {
        PhoenixManager phoenixManager = new PhoenixManager(client);
        phoenixManager.getPhoenixMarkets().forEach(market -> {
            double quoteAtomsPerQuoteUnit = Math.pow(10, market.getPhoenixMarketHeader().getQuoteDecimals());
            log.info("Market: {}", market.getMarketId().toBase58());
            if (market.getBestBid().isPresent() && market.getBestAsk().isPresent()) {
                float bestBid =
                        ((float) market.getBestBid().get().getFirst().getPriceInTicks() / (float) quoteAtomsPerQuoteUnit) * market.getPhoenixMarketHeader().getQuoteLotSize() * market.getTickSizeInQuoteLotsPerBaseUnit();
                float bestAsk =
                        ((float) market.getBestAsk().get().getFirst().getPriceInTicks() / (float) quoteAtomsPerQuoteUnit) * market.getPhoenixMarketHeader().getQuoteLotSize() * market.getTickSizeInQuoteLotsPerBaseUnit();

                log.info(String.format("Bid: %.2f, Ask: %.2f", bestBid, bestAsk));
            }
        });

    }

    @Test
    public void orderNormalizedTest() {
        PhoenixManager phoenixManager = new PhoenixManager(client);
        phoenixManager.getPhoenixMarkets()
                .forEach(market -> {
                    log.info("Market: {}", market.getMarketId().toBase58());
                    // log.info("Detail: {}", market);
                    market.getBidListNormalized().forEach(phoenixOrder -> {
                        log.info(String.format("Bid: %.10f x %.4f", phoenixOrder.getPrice(), phoenixOrder.getSize()));
                    });
                    market.getAskListNormalized().forEach(phoenixOrder -> {
                        log.info(String.format("Ask: %.10f x %.4f", phoenixOrder.getPrice(), phoenixOrder.getSize()));
                    });
                });
    }

    @Test
    public void cancelAllOrdersWithFreeFundsTest() throws RpcException, IOException {
        Account tradingAccount = Account.fromJson(
                Resources.toString(
                        Resources.getResource(
                                "mikefsWLEcNYHgsiwSRr6PVd7yVcoKeaURQqeDE1tXN.json"),
                        Charset.defaultCharset()
                )
        );
        log.info("Trading account: {}", tradingAccount.getPublicKey().toBase58());

        // Claim Seat
        Transaction cancelOrdersWithFreeFundsTx = new Transaction();

        cancelOrdersWithFreeFundsTx.addInstruction(
                PhoenixProgram.cancelAllOrdersWithFreeFunds(
                        SOL_USDC_MARKET,
                        tradingAccount.getPublicKey()
                )
        );

        String claimSeatTxId = client.getApi().sendTransaction(
                cancelOrdersWithFreeFundsTx,
                List.of(tradingAccount),
                client.getApi().getRecentBlockhash(Commitment.PROCESSED)
        );
        log.info("CXL all orders: {}", claimSeatTxId);
    }

    @Test
    public void phoenixGetJitoSolMarketTest() throws RpcException {
        final AccountInfo marketAccountInfo = client.getApi().getAccountInfo(
                new PublicKey("2t9TBYyUyovhHQq434uAiBxW6DmJCg7w4xdDoSK6LRjP"),
                Map.of("commitment", Commitment.PROCESSED)
        );

        byte[] data = marketAccountInfo.getDecodedData();
        PhoenixMarket phoenixMarket = PhoenixMarket.readPhoenixMarket(data);

        var asks = phoenixMarket.getAskListSanitized().stream().sorted(
                (o1, o2) -> Math.toIntExact(o2.component1().getPriceInTicks() - o1.getFirst().getPriceInTicks())
        ).toList();
        asks.forEach(fifoOrderIdFIFORestingOrderPair -> {
            log.info(String.format("Ask: %.4f, Size: %.2f SOL, Trader: %s",
                    (double) fifoOrderIdFIFORestingOrderPair.getFirst().getPriceInTicks() / phoenixMarket.getPhoenixMarketHeader().getBaseLotSize(),
                    (double) fifoOrderIdFIFORestingOrderPair.getSecond().getNumBaseLots() / phoenixMarket.getBaseLotsPerBaseUnit(),
                    phoenixMarket.getTradersSanitized().get((int) (fifoOrderIdFIFORestingOrderPair.getSecond().getTraderIndex() - 1)).getFirst().toBase58()));
        });

        var bids = phoenixMarket.getBidListSanitized().stream().sorted(
                (o1, o2) -> Math.toIntExact(o2.component1().getPriceInTicks() - o1.getFirst().getPriceInTicks())
        ).toList();
        bids.forEach(fifoOrderIdFIFORestingOrderPair -> {
            log.info(String.format("Bid: %.4f, Size: %.2f SOL, Trader: %s",
                    (double) fifoOrderIdFIFORestingOrderPair.getFirst().getPriceInTicks() / phoenixMarket.getPhoenixMarketHeader().getBaseLotSize(),
                    (double) fifoOrderIdFIFORestingOrderPair.getSecond().getNumBaseLots() / phoenixMarket.getBaseLotsPerBaseUnit(),
                    phoenixMarket.getTradersSanitized().get((int) (fifoOrderIdFIFORestingOrderPair.getSecond().getTraderIndex() - 1)).getFirst().toBase58()));
        });
    }

    @Test
    public void phoenixGetMarketDetailStreamingTest() throws InterruptedException {
        final SubscriptionWebSocketClient mainnet = SubscriptionWebSocketClient.getInstance(
                Cluster.MAINNET.getEndpoint()
        );

        mainnet.accountSubscribe(
                SOL_USDC_MARKET.toBase58(),
                data -> {
                    log.info("Top 3 bids:");
                    Map<String, Object> map = (Map<String, Object>) data;
                    String base64 = (String) ((List) map.get("data")).get(0);
                    byte[] bytes = Base64.getDecoder().decode(base64);
                    PhoenixMarket phoenixMarket = PhoenixMarket.readPhoenixMarket(
                            bytes
                    );

                    var bids = phoenixMarket.getBidListSanitized().stream().sorted(
                            (o1, o2) -> Math.toIntExact(o2.component1().getPriceInTicks() - o1.getFirst().getPriceInTicks())
                    ).toList();
                    bids = bids.subList(0, 3);
                    bids.forEach(fifoOrderIdFIFORestingOrderPair -> {
                        log.info(String.format("Bid: $%.4f, Size: %.2f SOL, Trader: %s",
                                (double) fifoOrderIdFIFORestingOrderPair.getFirst().getPriceInTicks() / phoenixMarket.getBaseLotsPerBaseUnit(),
                                (double) fifoOrderIdFIFORestingOrderPair.getSecond().getNumBaseLots() / phoenixMarket.getTickSizeInQuoteLotsPerBaseUnit(),
                                phoenixMarket.getTradersSanitized().get((int) (fifoOrderIdFIFORestingOrderPair.getSecond().getTraderIndex() - 1)).getFirst().toBase58()));
                    });

                    var asks = phoenixMarket.getAskListSanitized().stream().sorted(
                            (o1, o2) -> Math.toIntExact(o1.component1().getPriceInTicks() - o2.getFirst().getPriceInTicks())
                    ).toList();
                    asks = asks.subList(0, 3);

                    asks.forEach(fifoOrderIdFIFORestingOrderPair -> {
                        log.info(String.format("Ask: $%.4f, Size: %.2f SOL, Trader: %s",
                                (double) fifoOrderIdFIFORestingOrderPair.getFirst().getPriceInTicks() / phoenixMarket.getBaseLotsPerBaseUnit(),
                                (double) fifoOrderIdFIFORestingOrderPair.getSecond().getNumBaseLots() / phoenixMarket.getTickSizeInQuoteLotsPerBaseUnit(),
                                phoenixMarket.getTradersSanitized().get((int) (fifoOrderIdFIFORestingOrderPair.getSecond().getTraderIndex() - 1)).getFirst().toBase58()));
                    });
                }
        );

        Thread.sleep(120_000);
    }

    @Test
    public void phoenixGetMarketDetailTest() throws RpcException, IOException {
        final AccountInfo marketAccountInfo = client.getApi().getAccountInfo(
                SOL_USDC_MARKET,
                Map.of("commitment", Commitment.PROCESSED)
        );

        Files.write(marketAccountInfo.getDecodedData(), new File("phoenixMarket.bin"));

        byte[] data = marketAccountInfo.getDecodedData();
        // 576 = start of pub _padding: [u64; 32],v for market struct
        int marketStartOffset = 576;
        int baseLotsPerBaseUnitOffset = 832; // start at base lots to ignore padding
        // pub tick_size_in_quote_lots_per_base_unit: u64,
        int tickSizeInQuoteLotsPerBaseUnitOffset = baseLotsPerBaseUnitOffset + 8;

        log.info("Market start: " + marketStartOffset);
        log.info("Base per base unit offset: " + baseLotsPerBaseUnitOffset);
        log.info("Market detail length: {}", marketAccountInfo.getDecodedData().length);

        // Deserialization

        long baseLotsPerBaseUnit = Utils.readInt64(data, baseLotsPerBaseUnitOffset);
        long tickSizeInQuoteLotsPerBaseUnit = Utils.readInt64(data, tickSizeInQuoteLotsPerBaseUnitOffset);

        log.info("Base lots per base unit: {}", baseLotsPerBaseUnit);
        log.info("Tick size in quote lots per base unit: {}", tickSizeInQuoteLotsPerBaseUnit);

        PhoenixMarket phoenixMarket = PhoenixMarket.readPhoenixMarket(data);
        log.info("Phoenix market: {}", phoenixMarket.toString());
        log.info("Header from market: {}", phoenixMarket.getPhoenixMarketHeader().toString());
        log.info("Bids size: {}, Asks Size: {}, Number of seats: {}", phoenixMarket.getPhoenixMarketHeader().getBidsSize(), phoenixMarket.getPhoenixMarketHeader().getAsksSize(),
                phoenixMarket.getPhoenixMarketHeader().getNumSeats());

        var asks = phoenixMarket.getAskListSanitized().stream().sorted(
                (o1, o2) -> Math.toIntExact(o2.component1().getPriceInTicks() - o1.getFirst().getPriceInTicks())
        ).toList();
        asks.forEach(fifoOrderIdFIFORestingOrderPair -> {
            log.info(String.format("Ask: $%.2f, Size: %.2f SOL, Trader: %s",
                    (double) fifoOrderIdFIFORestingOrderPair.getFirst().getPriceInTicks() / phoenixMarket.getTickSizeInQuoteLotsPerBaseUnit(),
                    (double) fifoOrderIdFIFORestingOrderPair.getSecond().getNumBaseLots() / phoenixMarket.getBaseLotsPerBaseUnit(),
                    phoenixMarket.getTradersSanitized().get((int) (fifoOrderIdFIFORestingOrderPair.getSecond().getTraderIndex() - 1)).getFirst().toBase58()));
        });

        var bids = phoenixMarket.getBidListSanitized().stream().sorted(
                (o1, o2) -> Math.toIntExact(o2.component1().getPriceInTicks() - o1.getFirst().getPriceInTicks())
        ).toList();
        bids.forEach(fifoOrderIdFIFORestingOrderPair -> {
            log.info(String.format("Bid: $%.2f, Size: %.2f SOL, Trader: %s",
                    (double) fifoOrderIdFIFORestingOrderPair.getFirst().getPriceInTicks() / phoenixMarket.getTickSizeInQuoteLotsPerBaseUnit(),
                    (double) fifoOrderIdFIFORestingOrderPair.getSecond().getNumBaseLots() / phoenixMarket.getBaseLotsPerBaseUnit(),
                    phoenixMarket.getTradersSanitized().get((int) (fifoOrderIdFIFORestingOrderPair.getSecond().getTraderIndex() - 1)).getFirst().toBase58()));
        });

        var traders = phoenixMarket.getTradersSanitized();
//        traders.forEach((publicKey, phoenixTraderState) -> {
//            log.info("Trader Pubkey: {}, State: {}", publicKey.toBase58(), phoenixTraderState.toString());
//        });

        for (int i = 0; i < traders.size(); i++) {
            log.info("Index: {}, Trader Pubkey: {}, State: {}", i, traders.get(i).component1(),
                    traders.get(i).component2().toString());
        }

        log.info("Best Bid: {}", phoenixMarket.getBestBid());
        log.info("Best Ask: {}", phoenixMarket.getBestAsk());
    }

    @Test
    public void phoenixClaimSeatTest() throws RpcException, IOException {
        Account tradingAccount = Account.fromJson(
                Resources.toString(
                        Resources.getResource(
                                "mikefsWLEcNYHgsiwSRr6PVd7yVcoKeaURQqeDE1tXN.json"),
                        Charset.defaultCharset()
                )
        );
        log.info("Trading account: {}", tradingAccount.getPublicKey().toBase58());

        // Claim Seat
        Transaction claimSeatTransaction = new Transaction();

        claimSeatTransaction.addInstruction(
                ComputeBudgetProgram.setComputeUnitPrice(
                        100_000
                )
        );

        claimSeatTransaction.addInstruction(
                ComputeBudgetProgram.setComputeUnitLimit(
                        100_000
                )
        );

        claimSeatTransaction.addInstruction(
                PhoenixSeatManagerProgram.claimSeat(
                        SOL_USDC_MARKET,
                        SOL_USDC_SEAT_MANAGER,
                        tradingAccount.getPublicKey(),
                        tradingAccount.getPublicKey()
                )
        );

        String claimSeatTxId = client.getApi().sendTransaction(
                claimSeatTransaction,
                List.of(tradingAccount),
                client.getApi().getRecentBlockhash(Commitment.PROCESSED)
        );
        log.info("Claimed seat in transaction: {}", claimSeatTxId);
    }

    @Test
    public void phoenixPlaceLimitOrderTest() throws IOException, RpcException, InterruptedException {
        final AccountInfo marketAccountInfo = client.getApi().getAccountInfo(
                SOL_USDC_MARKET,
                Map.of("commitment", Commitment.PROCESSED)
        );

        byte[] data = marketAccountInfo.getDecodedData();
        PhoenixMarket market = PhoenixMarket.readPhoenixMarket(data);

        Account tradingAccount = Account.fromJson(
                Resources.toString(
                        Resources.getResource(
                                "mikefsWLEcNYHgsiwSRr6PVd7yVcoKeaURQqeDE1tXN.json"),
                        Charset.defaultCharset()
                )
        );
        log.info("Trading account: {}", tradingAccount.getPublicKey().toBase58());

        while (true) {

            LimitOrderPacketRecord limitOrderPacketRecord = LimitOrderPacketRecord.builder()
                    .clientOrderId(new byte[]{})
                    .matchLimit(0)
                    .numBaseLots(18L)
                    .priceInTicks((long) (market.getBestBid().get().getFirst().getPriceInTicks() * .9995))
                    .selfTradeBehavior((byte) 1)
                    .side((byte) 0)
                    .useOnlyDepositedFunds(false)
                    .build();

            LimitOrderPacketRecord limitOrderPacketRecordAsk = LimitOrderPacketRecord.builder()
                    .clientOrderId(new byte[]{})
                    .matchLimit(0)
                    .numBaseLots(18L)
                    .priceInTicks((long) (market.getBestAsk().get().getFirst().getPriceInTicks() * 1.0005))
                    .selfTradeBehavior((byte) 1)
                    .side((byte) 1)
                    .useOnlyDepositedFunds(false)
                    .build();

            Transaction limitOrderTx = new Transaction();
            limitOrderTx.addInstruction(
                    ComputeBudgetProgram.setComputeUnitPrice(
                            123
                    )
            );

            limitOrderTx.addInstruction(
                    ComputeBudgetProgram.setComputeUnitLimit(
                            130_000
                    )
            );
            limitOrderTx.addInstruction(
                    PhoenixSeatManagerProgram.claimSeat(
                            SOL_USDC_MARKET,
                            SOL_USDC_SEAT_MANAGER,
                            tradingAccount.getPublicKey(),
                            tradingAccount.getPublicKey()
                    )
            );

            limitOrderTx.addInstruction(
                    PhoenixProgram.cancelAllOrders(
                            SOL_USDC_MARKET,
                            tradingAccount.getPublicKey(),
                            new PublicKey("Avs5RSYyecvLnt9iFYNQX5EMUun3egh3UNPw8P6ULbNS"),
                            new PublicKey("A6Jcj1XV6QqDpdimmL7jm1gQtSP62j8BWbyqkdhe4eLe"),
                            market.getPhoenixMarketHeader().getBaseVaultKey(),
                            market.getPhoenixMarketHeader().getQuoteVaultKey()
                    )
            );

            limitOrderTx.addInstruction(
                    PhoenixProgram.placeLimitOrder(
                            SOL_USDC_MARKET,
                            tradingAccount.getPublicKey(),
                            new PublicKey("Avs5RSYyecvLnt9iFYNQX5EMUun3egh3UNPw8P6ULbNS"),
                            new PublicKey("A6Jcj1XV6QqDpdimmL7jm1gQtSP62j8BWbyqkdhe4eLe"),
                            market.getPhoenixMarketHeader().getBaseVaultKey(),
                            market.getPhoenixMarketHeader().getQuoteVaultKey(),
                            limitOrderPacketRecord
                    )
            );

            limitOrderTx.addInstruction(
                    PhoenixProgram.placeLimitOrder(
                            SOL_USDC_MARKET,
                            tradingAccount.getPublicKey(),
                            new PublicKey("Avs5RSYyecvLnt9iFYNQX5EMUun3egh3UNPw8P6ULbNS"),
                            new PublicKey("A6Jcj1XV6QqDpdimmL7jm1gQtSP62j8BWbyqkdhe4eLe"),
                            market.getPhoenixMarketHeader().getBaseVaultKey(),
                            market.getPhoenixMarketHeader().getQuoteVaultKey(),
                            limitOrderPacketRecordAsk
                    )
            );

            String placeLimitOrderTx = client.getApi().sendTransaction(
                    limitOrderTx,
                    List.of(tradingAccount),
                    client.getApi().getRecentBlockhash(Commitment.PROCESSED)
            );
            log.info("Limit order in transaction: {}, {}", limitOrderPacketRecord, placeLimitOrderTx);

            Thread.sleep(1000L);
            market = PhoenixMarket.readPhoenixMarket(
                    client.getApi().getAccountInfo(
                            SOL_USDC_MARKET,
                            Map.of("commitment", Commitment.PROCESSED)
                    ).getDecodedData()
            );
//
//            Transaction cancelOrdersTransaction = new Transaction();
//            cancelOrdersTransaction.addInstruction(
//                    ComputeBudgetProgram.setComputeUnitPrice(
//                            1_000_000
//                    )
//            );
//
//            cancelOrdersTransaction.addInstruction(
//                    ComputeBudgetProgram.setComputeUnitLimit(
//                            200_000
//                    )
//            );
//
//            cancelOrdersTransaction.addInstruction(
//                    PhoenixProgram.cancelAllOrders(
//                            SOL_USDC_MARKET,
//                            tradingAccount.getPublicKey(),
//                            new PublicKey("Avs5RSYyecvLnt9iFYNQX5EMUun3egh3UNPw8P6ULbNS"),
//                            new PublicKey("A6Jcj1XV6QqDpdimmL7jm1gQtSP62j8BWbyqkdhe4eLe"),
//                            market.getPhoenixMarketHeader().getBaseVaultKey(),
//                            market.getPhoenixMarketHeader().getQuoteVaultKey()
//                    )
//            );
//
//            String cancelAllOrdersTx = client.getApi().sendTransaction(
//                    cancelOrdersTransaction,
//                    List.of(tradingAccount),
//                    client.getApi().getRecentBlockhash(Commitment.PROCESSED)
//            );
//            log.info("Cxl all orders: {}", cancelAllOrdersTx);
//
//            Thread.sleep(1000);
        }
    }

    private String getDiscriminator(String input) {
        Keccak keccak = new Keccak(256);
        keccak.update(PhoenixProgram.PHOENIX_PROGRAM_ID.toByteArray());
        keccak.update(input.getBytes());

        ByteBuffer keccakBuffer = keccak.digest();
        keccakBuffer.order(ByteOrder.LITTLE_ENDIAN);
        byte[] keccakBytes = keccakBuffer.array();

        return Base58.encode(Arrays.copyOfRange(keccakBytes, 0, 8));
    }
}