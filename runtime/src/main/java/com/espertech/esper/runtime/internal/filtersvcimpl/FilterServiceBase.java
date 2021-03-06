/*
 ***************************************************************************************
 *  Copyright (C) 2006 EsperTech, Inc. All rights reserved.                            *
 *  http://www.espertech.com/esper                                                     *
 *  http://www.espertech.com                                                           *
 *  ---------------------------------------------------------------------------------- *
 *  The software in this package is published under the terms of the GPL license       *
 *  a copy of which has been included with this distribution in the license.txt file.  *
 ***************************************************************************************
 */
package com.espertech.esper.runtime.internal.filtersvcimpl;

import com.espertech.esper.common.client.EventBean;
import com.espertech.esper.common.client.EventType;
import com.espertech.esper.common.client.meta.EventTypeIdPair;
import com.espertech.esper.common.internal.filterspec.FilterValueSetParam;
import com.espertech.esper.common.internal.filtersvc.FilterHandle;
import com.espertech.esper.common.internal.metrics.audit.AuditPath;
import com.espertech.esper.runtime.internal.metrics.instrumentation.InstrumentationHelper;
import com.espertech.esper.runtime.internal.metrics.jmx.JmxGetter;
import com.espertech.esper.runtime.internal.metrics.jmx.JmxOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * Implementation of the filter service interface.
 * Does not allow the same filter callback to be added more then once.
 */
public abstract class FilterServiceBase implements FilterServiceSPI {
    private final FilterServiceGranularLockFactory lockFactory;
    private static final Logger log = LoggerFactory.getLogger(FilterServiceBase.class);
    private final EventTypeIndexBuilder indexBuilder;
    private final EventTypeIndex eventTypeIndex;
    private final AtomicLong numEventsEvaluated = new AtomicLong();
    private volatile long filtersVersion = 1;
    private final CopyOnWriteArraySet<FilterServiceListener> filterServiceListeners;

    protected FilterServiceBase(FilterServiceGranularLockFactory lockFactory, boolean allowIsolation) {
        this.lockFactory = lockFactory;
        eventTypeIndex = new EventTypeIndex(lockFactory);
        indexBuilder = new EventTypeIndexBuilder(eventTypeIndex, allowIsolation);
        filterServiceListeners = new CopyOnWriteArraySet<FilterServiceListener>();
    }

    public long getFiltersVersion() {
        return filtersVersion;
    }

    public void destroy() {
        log.debug("Destroying filter service");
        eventTypeIndex.destroy();
        indexBuilder.destroy();
    }

    protected void addInternal(EventType eventType, FilterValueSetParam[][] valueSet, FilterHandle filterCallback) {
        if (InstrumentationHelper.ENABLED) {
            InstrumentationHelper.get().qFilterAdd(eventType, valueSet, filterCallback);
        }

        indexBuilder.add(eventType, valueSet, filterCallback, lockFactory);
        filtersVersion++;

        if (InstrumentationHelper.ENABLED) {
            InstrumentationHelper.get().aFilterAdd();
        }
    }

    protected void removeInternal(FilterHandle filterCallback, EventType eventType, FilterValueSetParam[][] valueSet) {
        if (InstrumentationHelper.ENABLED) {
            InstrumentationHelper.get().qFilterRemove(filterCallback, eventType, valueSet);
        }

        indexBuilder.remove(filterCallback, eventType, valueSet);
        filtersVersion++;

        if (InstrumentationHelper.ENABLED) {
            InstrumentationHelper.get().aFilterRemove();
        }
    }

    protected long evaluateInternal(EventBean theEvent, Collection<FilterHandle> matches) {
        if (InstrumentationHelper.ENABLED) {
            InstrumentationHelper.get().qFilter(theEvent);
        }

        long version = filtersVersion;
        numEventsEvaluated.incrementAndGet();

        // Finds all matching filters and return their callbacks.
        retryableMatchEvent(theEvent, matches);

        if ((AuditPath.isAuditEnabled) && (!filterServiceListeners.isEmpty())) {
            for (FilterServiceListener listener : filterServiceListeners) {
                listener.filtering(theEvent, matches, null);
            }
        }

        if (InstrumentationHelper.ENABLED) {
            InstrumentationHelper.get().aFilter(matches);
        }

        return version;
    }

    protected long evaluateInternal(EventBean theEvent, Collection<FilterHandle> matches, int statementId) {
        long version = filtersVersion;
        numEventsEvaluated.incrementAndGet();

        ArrayDeque<FilterHandle> allMatches = new ArrayDeque<FilterHandle>();

        // Finds all matching filters
        retryableMatchEvent(theEvent, allMatches);

        // Add statement matches to collection passed
        for (FilterHandle match : allMatches) {
            if (match.getStatementId() == statementId) {
                matches.add(match);
            }
        }

        if ((AuditPath.isAuditEnabled) && (!filterServiceListeners.isEmpty())) {
            for (FilterServiceListener listener : filterServiceListeners) {
                listener.filtering(theEvent, matches, statementId);
            }
        }

        return version;
    }

    @JmxGetter(name = "NumEventsEvaluated", description = "Number of events evaluated (main)")
    public final long getNumEventsEvaluated() {
        return numEventsEvaluated.get();
    }

    @JmxOperation(description = "Reset number of events evaluated")
    public void resetStats() {
        numEventsEvaluated.set(0);
    }

    public void addFilterServiceListener(FilterServiceListener filterServiceListener) {
        filterServiceListeners.add(filterServiceListener);
    }

    public void removeFilterServiceListener(FilterServiceListener filterServiceListener) {
        filterServiceListeners.remove(filterServiceListener);
    }

    protected Map<EventTypeIdPair, Map<Integer, List<FilterItem[]>>> getInternal(Set<Integer> statementIds) {
        return indexBuilder.get(statementIds);
    }

    @JmxGetter(name = "NumFiltersApprox", description = "Number of filters managed (approximately)")
    public int getFilterCountApprox() {
        return eventTypeIndex.getFilterCountApprox();
    }

    @JmxGetter(name = "NumEventTypes", description = "Number of event types considered")
    public int getCountTypes() {
        return eventTypeIndex.size();
    }

    public void init(Supplier<Collection<EventType>> availableTypes) {
        // no initialization required
    }

    protected void removeTypeInternal(EventType type) {
        eventTypeIndex.removeType(type);
    }

    private void retryableMatchEvent(EventBean theEvent, Collection<FilterHandle> matches) {
        // Install lock backoff exception handler that retries the evaluation.
        try {
            eventTypeIndex.matchEvent(theEvent, matches);
        } catch (FilterLockBackoffException ex) {
            // retry on lock back-off
            // lock-backoff may occur when stateful evaluations take place such as boolean expressions that are subqueries
            // statements that contain subqueries in pattern filter expression can themselves modify filters, leading to a theoretically possible deadlock
            long delayNs = 10;
            while (true) {
                try {
                    // yield
                    try {
                        Thread.sleep(0);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }

                    // delay
                    LockSupport.parkNanos(delayNs);
                    if (delayNs < 1000000000) {
                        delayNs = delayNs * 2;
                    }

                    // evaluate
                    matches.clear();
                    eventTypeIndex.matchEvent(theEvent, matches);
                    break;
                } catch (FilterLockBackoffException ex2) {
                    // retried
                }
            }
        }
    }
}
