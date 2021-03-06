package se.maypril.metrics;

import com.codahale.metrics.*;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.MongoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.maypril.metrics.entity.*;

import java.util.Date;
import java.util.Map;
import java.util.SortedMap;
import java.util.concurrent.TimeUnit;

/**
 * A reporter which stores metrics to an mongodb server.
 *
 * @author callegustafsson
 */
public class MongoDBReporter extends ScheduledReporter {


    /**
     * Returns a new {@link Builder} for {@link MongoDBReporter}.
     *
     * @param registry the registry to report
     * @return a {@link Builder} instance for a {@link MongoDBReporter}
     */
    public static Builder forRegistry(final MetricRegistry registry) {
        return new Builder(registry);
    }

    public static class Builder {
        private final MetricRegistry registry;
        private Clock clock;
        private String prefix;
        private TimeUnit rateUnit;
        private TimeUnit durationUnit;
        private MetricFilter filter;

        private Builder(final MetricRegistry registry) {
            this.registry = registry;
            this.clock = Clock.defaultClock();
            this.prefix = null;
            this.rateUnit = TimeUnit.SECONDS;
            this.durationUnit = TimeUnit.MILLISECONDS;
            this.filter = MetricFilter.ALL;

        }

        /**
         * Use the given {@link Clock} instance for the time.
         *
         * @param clock a {@link Clock} instance
         * @return {@code this}
         */
        public Builder withClock(final Clock clock) {
            this.clock = clock;
            return this;
        }

        /**
         * Prefix all metric names with the given string.
         *
         * @param prefix the prefix for all metric names
         * @return {@code this}
         */
        public Builder prefixedWith(final String prefix) {
            this.prefix = prefix;
            return this;
        }

        /**
         * Convert rates to the given time unit.
         *
         * @param rateUnit a unit of time
         * @return {@code this}
         */
        public Builder convertRatesTo(final TimeUnit rateUnit) {
            this.rateUnit = rateUnit;
            return this;
        }

        /**
         * Convert durations to the given time unit.
         *
         * @param durationUnit a unit of time
         * @return {@code this}
         */
        public Builder convertDurationsTo(final TimeUnit durationUnit) {
            this.durationUnit = durationUnit;
            return this;
        }

        /**
         * Only report metrics which match the given filter.
         *
         * @param filter a {@link MetricFilter}
         * @return {@code this}
         */
        public Builder filter(final MetricFilter filter) {
            this.filter = filter;
            return this;
        }


        public MongoDBReporter build(final DB database) {
            return new MongoDBReporter(registry, database, clock, prefix, rateUnit, durationUnit, filter);

        }


    }

    private static final Logger LOGGER = LoggerFactory.getLogger(MongoDBReporter.class);
    private final Clock clock;
    private final String prefix;
    private final DB db;

    private MongoDBReporter(final MetricRegistry registry, final DB db, final Clock clock, final String prefix, final TimeUnit rateUnit, final TimeUnit durationUnit,
                            final MetricFilter filter) {
        super(registry, "mongodb-reporter", filter, rateUnit, durationUnit);
        LOGGER.trace("Creating MongoDBReporter: {} {} {} {} {} {}", registry, db, clock, prefix, rateUnit, durationUnit, filter);
        this.db = db;
        this.clock = clock;
        this.prefix = prefix;
    }

    @Override
    public void report(final SortedMap<String, Gauge> gauges, final SortedMap<String, Counter> counters, final SortedMap<String, Histogram> histograms,
                       final SortedMap<String, Meter> meters, final SortedMap<String, Timer> timers) {
        final long timestampClock = clock.getTime();
        final Date timestamp = new Date(timestampClock);

        for (final Map.Entry<String, Gauge> entry : gauges.entrySet()) {
            reportGauge(entry.getKey(), entry.getValue(), timestamp);
        }

        for (final Map.Entry<String, Counter> entry : counters.entrySet()) {
            reportCounter(entry.getKey(), entry.getValue(), timestamp);
        }

        for (final Map.Entry<String, Histogram> entry : histograms.entrySet()) {
            reportHistogram(entry.getKey(), entry.getValue(), timestamp);
        }

        for (final Map.Entry<String, Meter> entry : meters.entrySet()) {
            reportMetered(entry.getKey(), entry.getValue(), timestamp);
        }

        for (final Map.Entry<String, Timer> entry : timers.entrySet()) {
            reportTimer(entry.getKey(), entry.getValue(), timestamp);
        }

    }

    private void reportTimer(final String name, final Timer timer, final Date timestamp) {
        final DBCollection coll = db.getCollection("metric_timer");
        final TimerEntity entity = new TimerEntity(timer);
        entity.setName(prefix(name));
        entity.setTimestamp(timestamp);

        try {
            coll.save(entity.toDBObject());
        } catch (MongoException e) {
            LOGGER.warn("Unable to report timer {}", name, e);
        }
    }

    private void reportMetered(final String name, final Metered meter, final Date timestamp) {
        final DBCollection coll = db.getCollection("metric_metered");
        final MeteredEntity entity = new MeteredEntity(meter);
        entity.setName(prefix(name));
        entity.setTimestamp(timestamp);

        try {
            coll.save(entity.toDBObject());
        } catch (MongoException e) {
            LOGGER.warn("Unable to report meter {}", name, e);
        }
    }

    private void reportHistogram(final String name, final Histogram histogram, final Date timestamp) {
        final Snapshot snapshot = histogram.getSnapshot();

        final DBCollection coll = db.getCollection("metric_histogram");
        final HistogramEntity entity = new HistogramEntity(snapshot);
        entity.setName(prefix(name));
        entity.setCount(histogram.getCount());
        entity.setTimestamp(timestamp);

        try {
            coll.save(entity.toDBObject());
        } catch (MongoException e) {
            LOGGER.warn("Unable to report histogram {}", name, e);
        }
    }

    private void reportCounter(final String name, final Counter counter, final Date timestamp) {
        final DBCollection coll = db.getCollection("metric_counter");

        final CounterEntity entity = new CounterEntity();
        entity.setName(prefix(name, "count"));
        entity.setCount(counter.getCount());
        entity.setTimestamp(timestamp);

        try {
            coll.save(entity.toDBObject());
        } catch (MongoException e) {
            LOGGER.warn("Unable to report counter {}", name, e);
        }
    }

    private void reportGauge(final String name, final Gauge gauge, final Date timestamp) {
        final DBCollection coll = db.getCollection("metric_gauge");
        final Object value = gauge.getValue();

        if (value == null) {
            // skip report
            return;
        }

        if (!String.class.equals(value.getClass())) {
            final GaugeEntity entity = new GaugeEntity();
            entity.setName(prefix(name));
            entity.setTimestamp(timestamp);
            entity.setValue(value);

            try {
                coll.save(entity.toDBObject());
            } catch (MongoException e) {
                LOGGER.warn("Unable to report gauge {}", name, e);
            }
        }
    }


    private String prefix(final String... components) {
        return MetricRegistry.name(prefix, components);
    }


}
