/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.api.internal.artifacts.ivyservice.ivyresolve;

import org.apache.ivy.Ivy;
import org.apache.ivy.core.IvyContext;
import org.apache.ivy.core.resolve.ResolveData;
import org.apache.ivy.core.resolve.ResolveOptions;
import org.apache.ivy.core.settings.IvySettings;
import org.gradle.api.artifacts.cache.ResolutionRules;
import org.gradle.api.internal.artifacts.configurations.ConfigurationInternal;
import org.gradle.api.internal.artifacts.ivyservice.CacheLockingManager;
import org.gradle.api.internal.artifacts.ivyservice.dynamicversions.ModuleResolutionCache;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.memcache.InMemoryDependencyMetadataCache;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.LatestStrategy;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.ResolverStrategy;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionMatcher;
import org.gradle.api.internal.artifacts.ivyservice.modulecache.ModuleDescriptorCache;
import org.gradle.api.internal.artifacts.repositories.ResolutionAwareRepository;
import org.gradle.api.internal.artifacts.repositories.resolver.ExternalResourceResolver;
import org.gradle.api.internal.externalresource.cached.CachedArtifactIndex;
import org.gradle.internal.TimeProvider;
import org.gradle.util.WrapUtil;

public class ResolveIvyFactory {
    private final ModuleResolutionCache moduleResolutionCache;
    private final ModuleDescriptorCache moduleDescriptorCache;
    private final CachedArtifactIndex artifactAtRepositoryCachedResolutionIndex;
    private final CacheLockingManager cacheLockingManager;
    private final StartParameterResolutionOverride startParameterResolutionOverride;
    private final TimeProvider timeProvider;
    private InMemoryDependencyMetadataCache inMemoryCache;

    public ResolveIvyFactory(ModuleResolutionCache moduleResolutionCache, ModuleDescriptorCache moduleDescriptorCache,
                             CachedArtifactIndex artifactAtRepositoryCachedResolutionIndex,
                             CacheLockingManager cacheLockingManager, StartParameterResolutionOverride startParameterResolutionOverride,
                             TimeProvider timeProvider, InMemoryDependencyMetadataCache inMemoryCache) {
        this.moduleResolutionCache = moduleResolutionCache;
        this.moduleDescriptorCache = moduleDescriptorCache;
        this.artifactAtRepositoryCachedResolutionIndex = artifactAtRepositoryCachedResolutionIndex;
        this.cacheLockingManager = cacheLockingManager;
        this.startParameterResolutionOverride = startParameterResolutionOverride;
        this.timeProvider = timeProvider;
        this.inMemoryCache = inMemoryCache;
    }

    public IvyAdapter create(ConfigurationInternal configuration, Iterable<? extends ResolutionAwareRepository> repositories) {
        ResolutionRules resolutionRules = configuration.getResolutionStrategy().getResolutionRules();
        startParameterResolutionOverride.addResolutionRules(resolutionRules);

        VersionMatcher versionMatcher = ResolverStrategy.INSTANCE.getVersionMatcher();
        LatestStrategy comparatorLatestStrategy = ResolverStrategy.INSTANCE.getLatestStrategy();

        UserResolverChain userResolverChain = new UserResolverChain(versionMatcher, comparatorLatestStrategy);

        for (ResolutionAwareRepository repository : repositories) {
            ConfiguredModuleVersionRepository moduleVersionRepository = repository.createResolver();

            if (moduleVersionRepository instanceof IvyAwareModuleVersionRepository) {
                ivyContextualize((IvyAwareModuleVersionRepository) moduleVersionRepository, userResolverChain, configuration.getName());
            }
            if (moduleVersionRepository instanceof ExternalResourceResolver) {
                // TODO:DAZ this should be cache-locking?
                // TODO:DAZ Should have type for this
                ((ExternalResourceResolver) moduleVersionRepository).setResolver(userResolverChain);
            }

            LocalAwareModuleVersionRepository localAwareRepository;
            if (moduleVersionRepository.isLocal()) {
                localAwareRepository = new LocalModuleVersionRepository(moduleVersionRepository);
            } else {
                ModuleVersionRepository wrapperRepository = new CacheLockingModuleVersionRepository(moduleVersionRepository, cacheLockingManager);
                wrapperRepository = startParameterResolutionOverride.overrideModuleVersionRepository(wrapperRepository);
                localAwareRepository = new CachingModuleVersionRepository(wrapperRepository, moduleResolutionCache, moduleDescriptorCache, artifactAtRepositoryCachedResolutionIndex,
                        userResolverChain, configuration.getResolutionStrategy().getCachePolicy(), timeProvider);
            }
            if (moduleVersionRepository.isDynamicResolveMode()) {
                localAwareRepository = new IvyDynamicResolveModuleVersionRepository(localAwareRepository);
            }
            localAwareRepository = inMemoryCache.cached(localAwareRepository);
            userResolverChain.add(localAwareRepository);
        }

        return new DefaultIvyAdapter(versionMatcher, comparatorLatestStrategy, userResolverChain);
    }

    private void ivyContextualize(IvyAwareModuleVersionRepository ivyAwareRepository, UserResolverChain userResolverChain, String configurationName) {
        // TODO:DAZ Fix it so that ivy is only initialised if/when required here
        Ivy ivy = IvyContext.getContext().getIvy();
        IvySettings ivySettings = ivy.getSettings();
        LoopbackDependencyResolver loopbackDependencyResolver = new LoopbackDependencyResolver("main", userResolverChain, cacheLockingManager);
        ivySettings.addResolver(loopbackDependencyResolver);
        ivySettings.setDefaultResolver(loopbackDependencyResolver.getName());

        ResolveData resolveData = createResolveData(ivy, configurationName);
        ivyAwareRepository.setSettings(ivySettings);
        ivyAwareRepository.setResolveData(resolveData);
    }

    private ResolveData createResolveData(Ivy ivy, String configurationName) {
        ResolveOptions options = new ResolveOptions();
        options.setDownload(false);
        options.setConfs(WrapUtil.toArray(configurationName));
        return new ResolveData(ivy.getResolveEngine(), options);
    }
}
