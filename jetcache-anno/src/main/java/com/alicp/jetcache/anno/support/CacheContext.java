/**
 * Created on  13-09-04 15:34
 */
package com.alicp.jetcache.anno.support;

import com.alicp.jetcache.AbstractCacheBuilder;
import com.alicp.jetcache.Cache;
import com.alicp.jetcache.CacheConfigException;
import com.alicp.jetcache.CacheManager;
import com.alicp.jetcache.MultiLevelCacheBuilder;
import com.alicp.jetcache.RefreshCache;
import com.alicp.jetcache.anno.CacheConsts;
import com.alicp.jetcache.anno.CacheType;
import com.alicp.jetcache.anno.EnableCache;
import com.alicp.jetcache.anno.method.CacheInvokeContext;
import com.alicp.jetcache.embedded.EmbeddedCacheBuilder;
import com.alicp.jetcache.external.ExternalCacheBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * @author <a href="mailto:areyouok@gmail.com">huangli</a>
 */
public class CacheContext {

    private static Logger logger = LoggerFactory.getLogger(CacheContext.class);

    private static ThreadLocal<CacheThreadLocal> cacheThreadLocal = new ThreadLocal<CacheThreadLocal>() {
        @Override
        protected CacheThreadLocal initialValue() {
            return new CacheThreadLocal();
        }
    };

    private ConfigProvider configProvider;
    private GlobalCacheConfig globalCacheConfig;

    protected CacheManager cacheManager;

    public CacheContext(ConfigProvider configProvider, GlobalCacheConfig globalCacheConfig) {
        this.globalCacheConfig = globalCacheConfig;
        this.configProvider = configProvider;
        cacheManager = configProvider.getCacheManager();
    }

    public CacheInvokeContext createCacheInvokeContext(ConfigMap configMap) {
        CacheInvokeContext c = newCacheInvokeContext();
        c.setCacheFunction((invokeContext, cacheAnnoConfig) -> {
            Cache cache = cacheAnnoConfig.getCache();
            if (cache == null) {
                if (cacheAnnoConfig instanceof CachedAnnoConfig) {
                    cache = createCacheByCachedConfig((CachedAnnoConfig) cacheAnnoConfig, invokeContext);
                } else if ((cacheAnnoConfig instanceof CacheInvalidateAnnoConfig) || (cacheAnnoConfig instanceof CacheUpdateAnnoConfig)) {
                    CachedAnnoConfig cac = configMap.getByCacheName(cacheAnnoConfig.getArea(), cacheAnnoConfig.getName());
                    if (cac == null) {
                        String message = "can't find @Cached definition with area=" + cacheAnnoConfig.getArea()
                                + " name=" + cacheAnnoConfig.getName() +
                                ", specified in " + cacheAnnoConfig.getDefineMethod();
                        CacheConfigException e = new CacheConfigException(message);
                        logger.error("Cache operation aborted because can't find @Cached definition", e);
                        return null;
                    }
                    cache = createCacheByCachedConfig(cac, invokeContext);
                }
                cacheAnnoConfig.setCache(cache);
            }
            return cache;
        });
        return c;
    }

    private Cache createCacheByCachedConfig(CachedAnnoConfig ac, CacheInvokeContext invokeContext) {
        String area = ac.getArea();
        String cacheName = ac.getName();
        if (CacheConsts.isUndefined(cacheName)) {

            cacheName = configProvider.createCacheNameGenerator(invokeContext.getHiddenPackages())
                    .generateCacheName(invokeContext.getMethod(), invokeContext.getTargetObject());
        }
        Cache cache = __createOrGetCache(ac, area, cacheName);
        return cache;
    }

    @Deprecated
    public <K, V> Cache<K, V> getCache(String cacheName) {
        return getCache(CacheConsts.DEFAULT_AREA, cacheName);
    }

    @Deprecated
    public <K, V> Cache<K, V> getCache(String area, String cacheName) {
        Cache cache = cacheManager.getCache(area, cacheName);
        return cache;
    }

    public Cache __createOrGetCache(CachedAnnoConfig cachedAnnoConfig, String area, String cacheName) {
        Cache cache = cacheManager.getCache(area, cacheName);
        if (cache == null) {
            synchronized (this) {
                cache = cacheManager.getCache(area, cacheName);
                if (cache == null) {
                    cache = buildCache(cachedAnnoConfig, area, cacheName);
                    cacheManager.putCache(area, cacheName, cache);
                }
            }
        }
        return cache;
    }

    protected Cache buildCache(CachedAnnoConfig cachedAnnoConfig, String area, String cacheName) {
        Cache cache;
        if (cachedAnnoConfig.getCacheType() == CacheType.LOCAL) {
            cache = buildLocal(cachedAnnoConfig, area);
        } else if (cachedAnnoConfig.getCacheType() == CacheType.REMOTE) {
            cache = buildRemote(cachedAnnoConfig, area, cacheName);
        } else {
            Cache local = buildLocal(cachedAnnoConfig, area);
            Cache remote = buildRemote(cachedAnnoConfig, area, cacheName);


            boolean useExpireOfSubCache = cachedAnnoConfig.getLocalExpire() > 0;
            cache = MultiLevelCacheBuilder.createMultiLevelCacheBuilder()
                    .expireAfterWrite(remote.config().getExpireAfterWriteInMillis(), TimeUnit.MILLISECONDS)
                    .addCache(local, remote)
                    .useExpireOfSubCache(useExpireOfSubCache)
                    .cacheNullValue(cachedAnnoConfig.isCacheNullValue())
                    .buildCache();
        }
        cache.config().setRefreshPolicy(cachedAnnoConfig.getRefreshPolicy());
        cache = new RefreshCache(cache);

        cache.config().setCachePenetrationProtect(globalCacheConfig.isPenetrationProtect());
        PenetrationProtectConfig protectConfig = cachedAnnoConfig.getPenetrationProtectConfig();
        if (protectConfig != null) {
            cache.config().setCachePenetrationProtect(protectConfig.isPenetrationProtect());
            cache.config().setPenetrationProtectTimeout(protectConfig.getPenetrationProtectTimeout());
        }

        if (configProvider.getCacheMonitorManager() != null) {
            configProvider.getCacheMonitorManager().addMonitors(area, cacheName, cache, cachedAnnoConfig.isSyncLocal());
        }
        return cache;
    }

    protected Cache buildRemote(CachedAnnoConfig cachedAnnoConfig, String area, String keyPrefix) {
        ExternalCacheBuilder cacheBuilder = (ExternalCacheBuilder) globalCacheConfig.getRemoteCacheBuilders().get(area);
        if (cacheBuilder == null) {
            throw new CacheConfigException("no remote cache builder: " + area);
        }
        cacheBuilder = (ExternalCacheBuilder) cacheBuilder.clone();

        if (cachedAnnoConfig.getExpire() > 0 ) {
            cacheBuilder.expireAfterWrite(cachedAnnoConfig.getExpire(), cachedAnnoConfig.getTimeUnit());
        }

        String fullPrefix;
        if (globalCacheConfig.isAreaInCacheName()) {
            // for compatible reason, if we use default configuration, the prefix should same to that version <=2.4.3
            fullPrefix = area + "_" + keyPrefix;
        } else {
            fullPrefix = keyPrefix;
        }
        if (cacheBuilder.getConfig().getKeyPrefixSupplier() != null) {
            Supplier<String> supplier = cacheBuilder.getConfig().getKeyPrefixSupplier();
            cacheBuilder.setKeyPrefixSupplier(() -> supplier.get() + fullPrefix);
        } else {
            cacheBuilder.setKeyPrefix(fullPrefix);
        }

        processKeyConvertor(cachedAnnoConfig, cacheBuilder);
        if (!CacheConsts.isUndefined(cachedAnnoConfig.getSerialPolicy())) {
            cacheBuilder.setValueEncoder(configProvider.parseValueEncoder(cachedAnnoConfig.getSerialPolicy()));
            cacheBuilder.setValueDecoder(configProvider.parseValueDecoder(cachedAnnoConfig.getSerialPolicy()));
        } else {
            if (cacheBuilder.getConfig().getValueEncoder() instanceof ParserFunction) {
                ParserFunction<Object, byte[]> f = (ParserFunction<Object, byte[]>) cacheBuilder.getConfig().getValueEncoder();
                cacheBuilder.setValueEncoder(configProvider.parseValueEncoder(f.getValue()));
            }
            if (cacheBuilder.getConfig().getValueDecoder() instanceof ParserFunction) {
                ParserFunction<byte[], Object> f = (ParserFunction<byte[], Object>) cacheBuilder.getConfig().getValueDecoder();
                cacheBuilder.setValueDecoder(configProvider.parseValueDecoder(f.getValue()));
            }
        }
        cacheBuilder.setCacheNullValue(cachedAnnoConfig.isCacheNullValue());
        return cacheBuilder.buildCache();
    }

    protected Cache buildLocal(CachedAnnoConfig cachedAnnoConfig, String area) {
        EmbeddedCacheBuilder cacheBuilder = (EmbeddedCacheBuilder) globalCacheConfig.getLocalCacheBuilders().get(area);
        if (cacheBuilder == null) {
            throw new CacheConfigException("no local cache builder: " + area);
        }
        cacheBuilder = (EmbeddedCacheBuilder) cacheBuilder.clone();

        if (cachedAnnoConfig.getLocalLimit() != CacheConsts.UNDEFINED_INT) {
            cacheBuilder.setLimit(cachedAnnoConfig.getLocalLimit());
        }
        if (cachedAnnoConfig.getCacheType() == CacheType.BOTH &&
                cachedAnnoConfig.getLocalExpire() > 0) {
            cacheBuilder.expireAfterWrite(cachedAnnoConfig.getLocalExpire(), cachedAnnoConfig.getTimeUnit());
        } else if (cachedAnnoConfig.getExpire() > 0) {
            cacheBuilder.expireAfterWrite(cachedAnnoConfig.getExpire(), cachedAnnoConfig.getTimeUnit());
        }
        processKeyConvertor(cachedAnnoConfig, cacheBuilder);
        cacheBuilder.setCacheNullValue(cachedAnnoConfig.isCacheNullValue());
        return cacheBuilder.buildCache();
    }

    private void processKeyConvertor(CachedAnnoConfig cachedAnnoConfig, AbstractCacheBuilder cacheBuilder) {
        if (!CacheConsts.isUndefined(cachedAnnoConfig.getKeyConvertor())) {
            cacheBuilder.setKeyConvertor(configProvider.parseKeyConvertor(cachedAnnoConfig.getKeyConvertor()));
        } else if (cacheBuilder.getConfig().getKeyConvertor() instanceof ParserFunction) {
            ParserFunction<Object, Object> f = (ParserFunction) cacheBuilder.getConfig().getKeyConvertor();
            cacheBuilder.setKeyConvertor(configProvider.parseKeyConvertor(f.getValue()));
        }
    }

    protected CacheInvokeContext newCacheInvokeContext() {
        return new CacheInvokeContext();
    }

    /**
     * Enable cache in current thread, for @Cached(enabled=false).
     *
     * @param callback
     * @see EnableCache
     */
    public static <T> T enableCache(Supplier<T> callback) {
        CacheThreadLocal var = cacheThreadLocal.get();
        try {
            var.setEnabledCount(var.getEnabledCount() + 1);
            return callback.get();
        } finally {
            var.setEnabledCount(var.getEnabledCount() - 1);
        }
    }

    protected static void enable() {
        CacheThreadLocal var = cacheThreadLocal.get();
        var.setEnabledCount(var.getEnabledCount() + 1);
    }

    protected static void disable() {
        CacheThreadLocal var = cacheThreadLocal.get();
        var.setEnabledCount(var.getEnabledCount() - 1);
    }

    protected static boolean isEnabled() {
        return cacheThreadLocal.get().getEnabledCount() > 0;
    }

}
