package com.itplace.userapi.map.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.repository.BenefitCarrierPolicyRepository;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.benefit.repository.CarrierTierBenefitRepository;
import com.itplace.userapi.common.redis.CacheConfig;
import com.itplace.userapi.map.dto.BenefitCacheDto;
import com.itplace.userapi.partner.entity.Partner;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.util.ByteUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PartnerBenefitCacheRedisTest {
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.4").withExposedPorts(6379);
    private LettuceConnectionFactory factory;
    private RedisCacheManager manager;
    private RedisCache cache;
    private CacheStatisticsCollector statistics;
    private BenefitRepository benefits;
    private PlatformTransactionManager transactions;
    private PartnerBenefitCacheService service;
    private final List<Thread> decodingThreads = new ArrayList<>();

    @BeforeEach
    void setUp() {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        try (var connection = factory.getConnection()) {
            connection.serverCommands().flushDb();
            connection.stringCommands().set(bytes("test:partner-benefits::__generation"), bytes("initial-generation"));
        }
        statistics = CacheStatisticsCollector.create();
        var original = (RedisCacheManager) new CacheConfig().cacheManager(factory, statistics);
        original.afterPropertiesSet();
        var configuration = original.getCacheConfigurations().get("partner-benefits");
        var values = configuration.getValueSerializationPair();
        var instrumentedValues = SerializationPair.fromSerializer(new RedisSerializer<Object>() {
            @Override public byte[] serialize(Object value) { return ByteUtils.getBytes(values.write(value)); }
            @Override public Object deserialize(byte[] value) {
                synchronized (decodingThreads) { decodingThreads.add(Thread.currentThread()); }
                return values.read(ByteBuffer.wrap(value));
            }
        });
        manager = RedisCacheManager.builder(RedisCacheWriter.nonLockingRedisCacheWriter(factory)
                        .withStatisticsCollector(statistics))
                .withInitialCacheConfigurations(Map.of("partner-benefits", configuration
                        .prefixCacheNameWith("test:").serializeValuesWith(instrumentedValues))).build();
        manager.afterPropertiesSet();
        cache = (RedisCache) manager.getCache("partner-benefits");
        benefits = mock(BenefitRepository.class);
        transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = service(benefits);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
        factory.destroy();
    }

    @Test
    void oneMgetKeepsLegacyBytesNullsPrefixesStatsAndDecodesOnCaller() {
        var populated = new ArrayList<>(List.of(new BenefitCacheDto(7L, "기존 혜택", new ArrayList<>())));
        cache.put(1L, populated);
        cache.put(2L, new ArrayList<>());
        cache.put(3L, null);
        statistics.reset("partner-benefits");
        long before = calls("mget");
        long beforeGet = calls("get");
        when(benefits.findAllByPartnerIdsWithPartner(List.of(4L))).thenReturn(List.of());

        var result = service.getBenefitsBatch(List.of(1L, 2L, 3L, 4L, 1L));

        assertThat(calls("mget") - before).isEqualTo(1);
        // miss fill의 Lua GET 1회뿐이며 partner별 GET은 발생하지 않는다.
        assertThat(calls("get") - beforeGet).isEqualTo(1);
        assertThat(result.get(1L)).usingRecursiveComparison().isEqualTo(populated);
        assertThat(result.get(2L)).isEmpty();
        assertThat(result.get(3L)).isEmpty();
        assertThat(result.get(4L)).isEmpty();
        assertThat(decodingThreads).containsExactly(Thread.currentThread(), Thread.currentThread());
        assertThat(cache.getStatistics().getGets()).isEqualTo(4);
        assertThat(cache.getStatistics().getHits()).isEqualTo(3);
        assertThat(cache.getStatistics().getMisses()).isEqualTo(1);
        assertThat(cache.getStatistics().getPuts()).isEqualTo(1);
        try (var connection = factory.getConnection()) {
            assertThat(connection.keyCommands().ttl(bytes("test:partner-benefits::4"))).isBetween(3299L, 3600L);
        }
        var definition = org.mockito.ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactions).getTransaction(definition.capture());
        assertThat(definition.getValue().isReadOnly()).isFalse();
        assertThat(definition.getValue().getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(definition.getValue().getTimeout()).isEqualTo(5);
    }

    @Test
    void invalidatesOnlyAfterCommitAndPreservesEntriesOnRollback() {
        cache.put(1L, new ArrayList<>());
        cache.put(2L, new ArrayList<>());
        TransactionSynchronizationManager.initSynchronization();
        service.invalidateAfterCommit(List.of(1L, 1L));
        assertThat(cache.get(1L)).isNotNull();
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        TransactionSynchronizationManager.clearSynchronization();
        assertThat(cache.get(1L)).isNull();
        assertThat(cache.get(2L)).isNotNull();
        TransactionSynchronizationManager.initSynchronization();
        service.invalidateAfterCommit(List.of(2L));
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        assertThat(cache.get(2L)).isNotNull();
    }

    @Test
    void anotherInstanceInvalidationRejectsOldFillAndNextRequestLoadsNewData() throws Exception {
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        when(benefits.findAllByPartnerIdsWithPartner(List.of(1L))).thenAnswer(call -> {
            started.countDown();
            assertThat(finish.await(5, TimeUnit.SECONDS)).isTrue();
            return List.of(benefit("old"));
        }).thenReturn(List.of(benefit("new")));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var oldRequest = executor.submit(() -> service.getBenefitsBatch(List.of(1L)));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            service(mock(BenefitRepository.class)).invalidateAfterCommit(List.of(1L));
            // 동일 JVM의 새 세대 요청도 아직 진행 중인 구세대 future에 합류하지 않는다.
            assertThat(service.getBenefits(1L).get(0).getBenefitName()).isEqualTo("new");
            finish.countDown();
            assertThat(oldRequest.get(5, TimeUnit.SECONDS).get(1L).get(0).getBenefitName()).isEqualTo("old");
            @SuppressWarnings("unchecked")
            var cached = (List<BenefitCacheDto>) cache.get(1L).get();
            assertThat(cached.get(0).getBenefitName()).isEqualTo("new");
        } finally { finish.countDown(); executor.shutdownNow(); }
    }

    @Test
    void overlappingColdBatchesSharePartnersWithoutCircularWait() throws Exception {
        var firstStarted = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondLoaded = new CountDownLatch(1);
        when(benefits.findAllByPartnerIdsWithPartner(List.of(1L, 2L))).thenAnswer(call -> {
            firstStarted.countDown();
            assertThat(releaseFirst.await(5, TimeUnit.SECONDS)).isTrue();
            return List.of();
        });
        when(benefits.findAllByPartnerIdsWithPartner(List.of(3L))).thenAnswer(call -> {
            secondLoaded.countDown();
            return List.of();
        });
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> service.getBenefitsBatch(List.of(1L, 2L)));
            assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> service.getBenefitsBatch(List.of(2L, 1L, 3L)));
            assertThat(secondLoaded.await(5, TimeUnit.SECONDS)).isTrue();
            releaseFirst.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).hasSize(2);
            assertThat(second.get(5, TimeUnit.SECONDS)).hasSize(3);
            verify(benefits, times(2)).findAllByPartnerIdsWithPartner(any());
        } finally { releaseFirst.countDown(); executor.shutdownNow(); }
    }

    @Test
    void failedLoadIsRemovedSoLaterRequestsCanRetry() {
        when(benefits.findAllByPartnerIdsWithPartner(List.of(1L)))
                .thenThrow(new IllegalStateException("DB unavailable")).thenReturn(List.of());
        assertThatThrownBy(() -> service.getBenefits(1L)).hasMessage("DB unavailable");
        assertThat(service.getBenefits(1L)).isEmpty();
        verify(benefits, times(2)).findAllByPartnerIdsWithPartner(List.of(1L));
    }

    private PartnerBenefitCacheService service(BenefitRepository repository) {
        return new PartnerBenefitCacheService(repository, mock(BenefitCarrierPolicyRepository.class),
                mock(CarrierTierBenefitRepository.class), manager, factory, statistics, transactions);
    }

    private Benefit benefit(String name) {
        return Benefit.builder().benefitId(7L).partner(Partner.builder().partnerId(1L).build())
                .benefitName(name).active(true).build();
    }

    private long calls(String command) {
        try (var connection = factory.getConnection()) {
            String info = connection.serverCommands().info("commandstats").getProperty("cmdstat_" + command);
            return info == null ? 0 : Long.parseLong(info.substring("calls=".length(), info.indexOf(',')));
        }
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
