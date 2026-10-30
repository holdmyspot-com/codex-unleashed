package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class CgroupBudget implements AutoCloseable {
    public enum Visibility { COMPLETE_BY_AUTHORITY, UNKNOWN }
    public enum Requirement { LIMIT_REQUIRED, NO_LIMIT_BY_AUTHORITY }
    public enum Kind { FINITE, UNLIMITED, UNAVAILABLE, NO_LOCAL_INTERFACE }
    public enum Status { COMPLETE, INCOMPLETE_VISIBILITY, UNAVAILABLE }

    public record Ancestor(Path group, Requirement requirement) {
        public Ancestor {
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(requirement, "requirement");
            if (!group.isAbsolute())
                throw new IllegalArgumentException("Group must be absolute: " + group);
            if (!group.normalize().equals(group))
                throw new IllegalArgumentException("Group must be normalized: " + group);
        }
    }

    public record Raw(int readStatus, boolean memoryControllerPresent,
                      int limitStatus, String limitText, int currentStatus, String currentText) {}

    public record Reading(Ancestor ancestor, Kind kind, BigInteger limit,
                          BigInteger current, Raw raw) {
        public Reading {
            Objects.requireNonNull(ancestor, "ancestor");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(raw, "raw");
            switch (kind) {
                case FINITE -> {
                    checkUnsigned64(limit);
                    checkUnsigned64(current);
                }
                case UNLIMITED -> {
                    if (limit != null)
                        throw new IllegalArgumentException("Unlimited reading must not have a finite limit");
                    checkUnsigned64(current);
                }
                case UNAVAILABLE, NO_LOCAL_INTERFACE -> {
                    if (limit != null || current != null)
                        throw new IllegalArgumentException("Unavailable or absent interface must not have numeric values");
                }
            }
        }
    }

    public record Sample(Visibility visibility, Status status, Long availableBytes,
                         List<Reading> readings) {
        public Sample {
            Objects.requireNonNull(visibility, "visibility");
            Objects.requireNonNull(status, "status");
            readings = List.copyOf(readings);
            if ((status == Status.COMPLETE) == (availableBytes == null))
                throw new IllegalArgumentException("Only a complete sample has available bytes");
            if (availableBytes != null && availableBytes < 0)
                throw new IllegalArgumentException("Available bytes must be nonnegative");
        }
    }

    private final Libcgroup nativeReader;

    private CgroupBudget(Libcgroup nativeReader) {
        this.nativeReader = nativeReader;
    }

    public static CgroupBudget open(Path library) throws IOException {
        return new CgroupBudget(Libcgroup.open(library));
    }

    public Sample read(long hostAvailableBytes, List<Ancestor> ancestors,
                       Visibility visibility) throws IOException {
        validateRequest(hostAvailableBytes, ancestors, visibility);
        var readings = new ArrayList<Reading>();
        for (var ancestor : ancestors)
            readings.add(classify(ancestor, nativeReader.read(ancestor.group())));
        return evaluate(hostAvailableBytes, readings, visibility);
    }

    public static Reading classify(Ancestor ancestor, Raw raw) {
        Objects.requireNonNull(ancestor, "ancestor");
        Objects.requireNonNull(raw, "raw");
        if (raw.readStatus() != 0)
            return new Reading(ancestor, Kind.UNAVAILABLE, null, null, raw);
        if (!raw.memoryControllerPresent())
            return new Reading(ancestor, Kind.NO_LOCAL_INTERFACE, null, null, raw);
        boolean bothValuesAbsent = raw.limitStatus() == 50015 && raw.currentStatus() == 50015;
        if (bothValuesAbsent)
            return new Reading(ancestor, Kind.NO_LOCAL_INTERFACE, null, null, raw);
        if (raw.limitStatus() != 0 || raw.currentStatus() != 0)
            return new Reading(ancestor, Kind.UNAVAILABLE, null, null, raw);

        try {
            var current = number(raw.currentText());
            if ("max".equals(raw.limitText()))
                return new Reading(ancestor, Kind.UNLIMITED, null, current, raw);
            var limit = number(raw.limitText());
            return new Reading(ancestor, Kind.FINITE, limit, current, raw);
        } catch (NumberFormatException failure) {
            return new Reading(ancestor, Kind.UNAVAILABLE, null, null, raw);
        }
    }

    public static Sample evaluate(long hostAvailableBytes, List<Reading> readings,
                                  Visibility visibility) {
        Objects.requireNonNull(readings, "readings");
        var ancestors = readings.stream().map(Reading::ancestor).toList();
        validateRequest(hostAvailableBytes, ancestors, visibility);
        var remaining = BigInteger.valueOf(hostAvailableBytes);
        boolean usable = true;
        for (var reading : readings) {
            switch (reading.kind()) {
                case FINITE -> {
                    var allowance = reading.limit().subtract(reading.current()).max(BigInteger.ZERO);
                    remaining = remaining.min(allowance);
                }
                case UNLIMITED -> {}
                case NO_LOCAL_INTERFACE -> {
                    if (reading.ancestor().requirement() == Requirement.LIMIT_REQUIRED)
                        usable = false;
                }
                case UNAVAILABLE -> usable = false;
            }
        }
        if (!usable)
            return new Sample(visibility, Status.UNAVAILABLE, null, readings);
        if (visibility == Visibility.UNKNOWN)
            return new Sample(visibility, Status.INCOMPLETE_VISIBILITY, null, readings);
        return new Sample(visibility, Status.COMPLETE, remaining.longValueExact(), readings);
    }

    private static void validateRequest(long hostAvailableBytes, List<Ancestor> ancestors,
                                        Visibility visibility) {
        Objects.requireNonNull(visibility, "visibility");
        List.copyOf(ancestors);
        if (hostAvailableBytes < 0)
            throw new IllegalArgumentException("Host available bytes must be nonnegative");
        if (ancestors.isEmpty())
            throw new IllegalArgumentException("Ancestor observations must include the workload parent");
        for (int index = 1; index < ancestors.size(); index++) {
            var expected = ancestors.get(index - 1).group().getParent();
            if (!ancestors.get(index).group().equals(expected))
                throw new IllegalArgumentException("Ancestor chain must ascend one group at a time");
        }
        boolean completeChain = ancestors.getLast().group().getParent() == null;
        if (visibility == Visibility.COMPLETE_BY_AUTHORITY && !completeChain)
            throw new IllegalArgumentException("Complete visibility requires observations through the hierarchy root");
    }

    private static void checkUnsigned64(BigInteger value) {
        Objects.requireNonNull(value, "value");
        if (value.signum() < 0 || value.bitLength() > 64)
            throw new IllegalArgumentException("Value is outside unsigned64: " + value);
    }

    private static BigInteger number(String text) {
        if (text == null)
            throw new NumberFormatException("Missing numeric text");
        var value = new BigInteger(text);
        boolean unsigned64 = value.signum() >= 0 && value.bitLength() <= 64;
        if (!unsigned64 || !value.toString().equals(text))
            throw new NumberFormatException("Value is not canonical unsigned64: " + text);
        return value;
    }

    @Override
    public void close() {
        nativeReader.close();
    }
}
