/*
 * This file is licensed under the MIT License, part of Roughly Enough Items.
 * Copyright (c) 2018, 2019, 2020, 2021, 2022, 2023 shedaniel
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package me.shedaniel.rei.impl.client.search;

import com.google.common.collect.Lists;
import dev.architectury.platform.Platform;
import me.shedaniel.rei.api.client.config.ConfigObject;
import me.shedaniel.rei.api.client.search.SearchFilter;
import me.shedaniel.rei.api.client.search.SearchProvider;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.util.CollectionUtils;
import me.shedaniel.rei.impl.client.util.ThreadCreator;
import me.shedaniel.rei.impl.common.InternalLogger;
import me.shedaniel.rei.impl.common.util.HashedEntryStackWrapper;
import net.minecraft.util.*;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;

public class AsyncSearchManager {
    private static final ExecutorService EXECUTOR_SERVICE = new ThreadCreator("REI-AsyncSearchManager").asService(Math.min(3, Runtime.getRuntime().availableProcessors()));
    private final Function<SearchFilter, List<? extends HashedEntryStackWrapper>> stacksProvider;
    private final Supplier<Predicate<HashedEntryStackWrapper>> additionalPredicateSupplier;
    private final UnaryOperator<HashedEntryStackWrapper> transformer;
    private volatile Map.Entry<List<HashedEntryStackWrapper>, SearchFilter> last;
    private final Object lifecycleLock = new Object();
    private volatile long generation;
    public volatile ExecutorTuple executor;
    public volatile SearchFilter filter;
    
    public AsyncSearchManager(Function<SearchFilter, List<? extends HashedEntryStackWrapper>> stacksProvider, Supplier<Predicate<HashedEntryStackWrapper>> additionalPredicateSupplier, UnaryOperator<HashedEntryStackWrapper> transformer) {
        this.stacksProvider = stacksProvider;
        this.additionalPredicateSupplier = additionalPredicateSupplier;
        this.transformer = transformer;
    }
    
    public void markDirty() {
        synchronized (this.lifecycleLock) {
            this.last = null;
            this.generation++;
            if (this.executor != null) {
                this.executor.future().cancel(Platform.isFabric());
                this.executor = null;
            }
        }
    }
    
    public record ExecutorTuple(SearchFilter filter,
                                 CompletableFuture<Map.Entry<List<HashedEntryStackWrapper>, SearchFilter>> future,
                                 Steps steps) {
    }
    
    public static class Steps {
        public long startTime = 0;
        public AtomicInteger partitionsDone = new AtomicInteger(0);
        public int totalPartitions = 0;
        private volatile long generation;
    }
    
    public void updateFilter(String filter) {
        synchronized (this.lifecycleLock) {
            if (this.filter == null || !this.filter.getFilter().equals(filter)) {
                this.generation++;
                this.last = null;
                if (this.executor != null) {
                    this.executor.future().cancel(Platform.isFabric());
                }
                this.executor = null;
                this.filter = SearchProvider.getInstance().createFilter(filter);
            }
        }
    }
    
    public boolean isDirty() {
        return this.last == null || this.last.getValue() != this.filter;
    }
    
    public long getGeneration() {
        return generation;
    }
    
    public Future<?> getAsync(BiConsumer<List<HashedEntryStackWrapper>, SearchFilter> consumer) {
        if (this.executor == null || this.executor.filter() != filter || isDirty()) {
            if (this.executor != null) {
                this.executor.future().cancel(Platform.isFabric());
            }
            SearchFilter savedFilter = this.filter;
            long searchGeneration = this.generation;
            Steps steps = new Steps();
            steps.generation = searchGeneration;
            this.executor = new ExecutorTuple(savedFilter, get(EXECUTOR_SERVICE, steps), steps);
        }
        ExecutorTuple executor = this.executor;
        SearchFilter savedFilter = executor.filter();
        long searchGeneration = executor.steps().generation;
        CompletableFuture<Map.Entry<List<HashedEntryStackWrapper>, SearchFilter>> future = executor.future().thenApplyAsync(result -> {
            if (!isCurrent(savedFilter, searchGeneration) || result.getValue() != savedFilter) {
                throw new CancellationException();
            }
            consumer.accept(result.getKey(), result.getValue());
            return result;
        }, EXECUTOR_SERVICE);
        synchronized (this.lifecycleLock) {
            if (this.executor == executor) {
                this.executor = new ExecutorTuple(executor.filter(), future, executor.steps());
            }
        }
        return future;
    }
    
    public List<HashedEntryStackWrapper> getNow() {
        try {
            return get(Runnable::run, new Steps()).get().getKey();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof CancellationException) {
                return Lists.newArrayList();
            }
            throw new RuntimeException(e);
        } catch (InterruptedException | CancellationException e) {
            return Lists.newArrayList();
        }
    }
    
    public CompletableFuture<Map.Entry<List<HashedEntryStackWrapper>, SearchFilter>> get(Executor executor, Steps steps) {
        SearchFilter searchFilter;
        long searchGeneration;
        Map.Entry<List<HashedEntryStackWrapper>, SearchFilter> last;
        synchronized (this.lifecycleLock) {
            searchFilter = this.filter;
            searchGeneration = this.generation;
            last = this.last;
        }
        steps.generation = searchGeneration;
        if (!isCurrent(searchFilter, searchGeneration)) {
            return cancelledFuture();
        }
        if (last == null || last.getValue() != searchFilter) {
            Predicate<HashedEntryStackWrapper> additionalPredicate = this.additionalPredicateSupplier.get();
            if (!isCurrent(searchFilter, searchGeneration)) {
                return cancelledFuture();
            }
            List<? extends HashedEntryStackWrapper> stacks = this.stacksProvider.apply(searchFilter);
            if (!isCurrent(searchFilter, searchGeneration)) {
                return cancelledFuture();
            }
            return get(searchFilter, additionalPredicate, this.transformer,
                    stacks, last, this, executor, steps, searchGeneration)
                    .thenApply(entry -> {
                        synchronized (this.lifecycleLock) {
                            if (!isCurrent(searchFilter, searchGeneration)) {
                                throw new CancellationException();
                            }
                            this.last = entry;
                        }
                        return entry;
                    })
                    .exceptionally(throwable -> {
                        if (!isCurrent(searchFilter, searchGeneration)) {
                            throw new CancellationException();
                        }
                        InternalLogger.getInstance().error("Error while searching", throwable);
                        return new AbstractMap.SimpleImmutableEntry<>(List.of(), searchFilter);
                    });
        }
        
        return CompletableFuture.completedFuture(last);
    }
    
    public static CompletableFuture<Map.Entry<List<HashedEntryStackWrapper>, SearchFilter>> get(SearchFilter filter, Predicate<HashedEntryStackWrapper> additionalPredicate,
            UnaryOperator<HashedEntryStackWrapper> transformer, List<? extends HashedEntryStackWrapper> stacks, Map.Entry<List<HashedEntryStackWrapper>, SearchFilter> last,
            AsyncSearchManager manager, Executor executor, Steps steps) {
        return get(filter, additionalPredicate, transformer, stacks, last, manager, executor, steps, manager.generation);
    }

    private static CompletableFuture<Map.Entry<List<HashedEntryStackWrapper>, SearchFilter>> get(SearchFilter filter, Predicate<HashedEntryStackWrapper> additionalPredicate,
            UnaryOperator<HashedEntryStackWrapper> transformer, List<? extends HashedEntryStackWrapper> stacks, Map.Entry<List<HashedEntryStackWrapper>, SearchFilter> last,
            AsyncSearchManager manager, Executor executor, Steps steps, long generation) {
        if (!manager.isCurrent(filter, generation)) {
            return cancelledFuture();
        }
        int searchPartitionSize = ConfigObject.getInstance().getAsyncSearchPartitionSize();
        boolean shouldAsync = ConfigObject.getInstance().shouldAsyncSearch() && stacks.size() > searchPartitionSize * 4;
        InternalLogger.getInstance().debug("Starting Search: \"" + filter.getFilter() + "\" with " + stacks.size() + " stacks, shouldAsync: " + shouldAsync + " on " + Thread.currentThread().getName());
        
        if (!stacks.isEmpty()) {
            if (shouldAsync) {
                List<CompletableFuture<List<HashedEntryStackWrapper>>> futures = Lists.newArrayList();
                int partitions = 0;
                for (Iterable<? extends HashedEntryStackWrapper> partitionStacks : CollectionUtils.partition(stacks, searchPartitionSize * 4)) {
                    futures.add(CompletableFuture.supplyAsync(() -> {
                        List<HashedEntryStackWrapper> filtered = Lists.newArrayList();
                        if (!manager.isCurrent(filter, generation)) throw new CancellationException();
                        for (HashedEntryStackWrapper stack : partitionStacks) {
                            if (stack != null && test(filter, stack.unwrap(), stack.hashExact()) && additionalPredicate.test(stack)) {
                                filtered.add(transformer.apply(stack));
                            }
                            if (!manager.isCurrent(filter, generation)) throw new CancellationException();
                        }
                        steps.partitionsDone.incrementAndGet();
                        return filtered;
                    }, executor));
                    partitions++;
                }
                steps.startTime = Util.getEpochMillis();
                steps.totalPartitions = partitions;
                InternalLogger.getInstance().debug("Async Search: " + partitions + " partitions for \"" + filter.getFilter() + "\"");
                return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .orTimeout(90, TimeUnit.SECONDS)
                        .thenApplyAsync($ -> {
                            List<HashedEntryStackWrapper> list = new ArrayList<>();
                            
                            for (CompletableFuture<List<HashedEntryStackWrapper>> future : futures) {
                                List<HashedEntryStackWrapper> now = future.getNow(null);
                                if (now != null) list.addAll(now);
                            }
                            if (!manager.isCurrent(filter, generation)) throw new CancellationException();
                            
                            return list;
                        }, executor)
                        .thenApply(result -> new AbstractMap.SimpleImmutableEntry<>(result, filter));
            } else {
                List<HashedEntryStackWrapper> list = new ArrayList<>();
                
                for (HashedEntryStackWrapper stack : stacks) {
                    if (test(filter, stack.unwrap(), stack.hashExact()) && additionalPredicate.test(stack)) {
                        list.add(transformer.apply(stack));
                    }
                    if (!manager.isCurrent(filter, generation)) throw new CancellationException();
                }
                
                return CompletableFuture.completedFuture(new AbstractMap.SimpleImmutableEntry<>(list, filter));
            }
        }
        
        return CompletableFuture.completedFuture(new AbstractMap.SimpleImmutableEntry<>(Lists.newArrayList(), filter));
    }
    
    public boolean isCurrent(SearchFilter expectedFilter, long expectedGeneration) {
        return this.filter == expectedFilter && this.generation == expectedGeneration;
    }

    private static <T> CompletableFuture<T> cancelledFuture() {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.cancel(false);
        return future;
    }

    private static boolean test(SearchFilter filter, EntryStack<?> stack, long hashExact) {
        try {
            return filter.test(stack, hashExact);
        } catch (Throwable throwable) {
            InternalLogger.getInstance().debug("Error while testing filter", throwable);
            return false;
        }
    }
    
    public boolean matches(EntryStack<?> stack) {
        return filter.test(stack);
    }
}
