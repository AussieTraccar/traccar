/*
 * Copyright 2014 - 2024 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.handler;

import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.database.StatisticsManager;
import org.traccar.helper.UnitsConverter;
import org.traccar.helper.model.AttributeUtil;
import org.traccar.model.Calendar;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import java.util.Date;

public class FilterHandler extends BasePositionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(FilterHandler.class);

    private final boolean filterInvalid;
    private final boolean filterZero;
    private final boolean filterDuplicate;
    private final boolean filterOutdated;
    private final long filterFuture;
    private final long filterPast;
    private final boolean filterApproximate;
    private final int filterAccuracy;
    private final double filterGt06Speed;
    private final double filterOsmandSpeed;
    private final boolean filterStatic;
    private final boolean filterStaticMove;
    private final double filterStaticThreshold;
    private final int filterDistance;
    private final int filterMaxSpeed;
    private final long filterMinPeriod;
    private final int filterDailyLimit;
    private final long filterDailyLimitInterval;
    private final boolean filterRelative;
    private final long skipLimit;
    private final boolean skipAttributes;

    private final CacheManager cacheManager;
    private final Storage storage;
    private final StatisticsManager statisticsManager;

    @Inject
    public FilterHandler(
            Config config, CacheManager cacheManager, Storage storage, StatisticsManager statisticsManager) {
        filterInvalid = config.getBoolean(Keys.FILTER_INVALID);
        filterZero = config.getBoolean(Keys.FILTER_ZERO);
        filterDuplicate = config.getBoolean(Keys.FILTER_DUPLICATE);
        filterOutdated = config.getBoolean(Keys.FILTER_OUTDATED);
        filterFuture = config.getLong(Keys.FILTER_FUTURE) * 1000;
        filterPast = config.getLong(Keys.FILTER_PAST) * 1000;
        filterAccuracy = config.getInteger(Keys.FILTER_ACCURACY);
        filterGt06Speed = config.getDouble(Keys.GT06_MIN_SPEED);
        filterOsmandSpeed = config.getDouble(Keys.OSMAND_MIN_SPEED);
        filterApproximate = config.getBoolean(Keys.FILTER_APPROXIMATE);
        filterStatic = config.getBoolean(Keys.FILTER_STATIC);
        filterStaticMove = config.getBoolean(Keys.FILTER_STATIC_MOVE);
        filterStaticThreshold = config.getDouble(Keys.EVENT_MOTION_SPEED_THRESHOLD);
        filterDistance = config.getInteger(Keys.FILTER_DISTANCE);
        filterMaxSpeed = config.getInteger(Keys.FILTER_MAX_SPEED);
        filterMinPeriod = config.getInteger(Keys.FILTER_MIN_PERIOD) * 1000L;
        filterDailyLimit = config.getInteger(Keys.FILTER_DAILY_LIMIT);
        filterDailyLimitInterval = config.getInteger(Keys.FILTER_DAILY_LIMIT_INTERVAL) * 1000L;
        filterRelative = config.getBoolean(Keys.FILTER_RELATIVE);
        skipLimit = config.getLong(Keys.FILTER_SKIP_LIMIT) * 1000;
        skipAttributes = config.getBoolean(Keys.FILTER_SKIP_ATTRIBUTES_ENABLE);
        this.cacheManager = cacheManager;
        this.storage = storage;
        this.statisticsManager = statisticsManager;
    }

    private Position getPrecedingPosition(long deviceId, Date date) throws StorageException {
        return storage.getObject(Position.class, new Request(
                new Columns.All(),
                new Condition.And(
                        new Condition.Equals("deviceId", deviceId),
                        new Condition.Compare("fixTime", "<=", "time", date)),
                new Order("fixTime", true, 1)));
    }

    private boolean filterInvalid(Position position) {
        return filterInvalid && (!position.getValid()
                || position.getLatitude() > 90 || position.getLongitude() > 180
                || position.getLatitude() < -90 || position.getLongitude() < -180);
    }

    private boolean filterZero(Position position) {
        return filterZero && position.getLatitude() == 0.0 && position.getLongitude() == 0.0;
    }

    private boolean filterDuplicate(Position position, Position last) {
        if (filterDuplicate && last != null && position.getFixTime().equals(last.getFixTime())) {
            for (String key : position.getAttributes().keySet()) {
                if (!last.hasAttribute(key)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private boolean filterOutdated(Position position) {
        return filterOutdated && position.getOutdated();
    }

    private boolean filterFuture(Position position) {
        return filterFuture != 0 && position.getFixTime().getTime() > System.currentTimeMillis() + filterFuture;
    }

    private boolean filterPast(Position position) {
        return filterPast != 0 && position.getFixTime().getTime() < System.currentTimeMillis() - filterPast;
    }

    private boolean filterAccuracy(Position position) {
        return filterAccuracy != 0 && position.getAccuracy() >= filterAccuracy;
    }

    private boolean filterApproximate(Position position) {
        return filterApproximate && position.getBoolean(Position.KEY_APPROXIMATE);
    }

    private boolean filterStatic(Position position, Position last, boolean log) {
        long deviceId = position.getDeviceId();
        Device device = cacheManager.getObject(Device.class, deviceId);

        if (filterStatic && last != null) {
            if (position.getSpeed() == 0.0 && last.getSpeed() == 0.0) {
                String string = AttributeUtil.lookup(cacheManager, Keys.FILTER_STATIC_PROTOCOLS, position.getDeviceId());
                if (!string.isEmpty()) {
                    for (String protocol : string.split("[ ,]")) {
                        if (position.getProtocol().equals(protocol)) {
                            if (log) {
                                LOGGER.info("Position Static with protocol {} from device: {}", protocol, device.getUniqueId());
                            }
                            return true;
                        }
                    }
                } else {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean filterStaticMove(Position position, Position last) {
        long deviceId = position.getDeviceId();
        Device device = cacheManager.getObject(Device.class, deviceId);

        if (filterStaticMove && last != null) {
            long speed = Math.round((position.getSpeed() * 0.2777777778));
            long distance = Math.round(position.getDouble(Position.KEY_DISTANCE));
            long time = position.getFixTime().getTime() - last.getFixTime().getTime();
            long calculatedKn = Math.round(UnitsConverter.knotsFromMps((double) distance / ((float) time / 1000)));
            long calculatedMps = Math.round(((float) distance / ((float) time / 1000)));
            if (distance != 0 && speed != 0) {
                LOGGER.info("Position filtered by StaticMovement-calcJump from device: {} (d:{} t:{} s:{}mps c:{}mps->{}kn)", device.getUniqueId(), distance, time, speed, calculatedMps, calculatedKn);
            }

            boolean motionState = device.getMotionState();
            boolean motionPosition = (position.getAttributes().get("motion") != null && (boolean) position.getAttributes().get("motion"));
            if (motionState || motionPosition) {
                LOGGER.info("Position filtered by StaticMovement-statePosition ({}/{}) from device: {}", motionState, motionPosition, device.getUniqueId());
            }

            StringBuilder filterStaticType = new StringBuilder();
            // filterStaticType.setLength(0);

            if (position.getBoolean(Position.KEY_IGNITION) || last.getBoolean(Position.KEY_IGNITION)) {
                LOGGER.info("Position NOT filtered by StaticMovement-onIgnition from device: {}", device.getUniqueId());
                return false;
            }
            // filter all devices in motion state and zero speed or distance values
            if (motionPosition) {
                if (position.getSpeed() == 0.0 && last.getSpeed() == 0.0 && distance == 0) {
                    filterStaticType.append("-noSpeed");
                } else {
                    if (position.getProtocol().equals("gt06")) {
                        // filter gt06 protocol devices in motion state and with minimum threshold speed value
                        if (filterGt06Speed != 0 && (position.getSpeed() > 0.0 && position.getSpeed() < filterGt06Speed)) {
                            filterStaticType.append("-minSpeed");
                        }
                    }
                    if (position.getProtocol().equals("osmand")) {
                        // filter osmand protocol devices in motion state and with minimum threshold speed value
                        if (filterOsmandSpeed != 0 && (position.getSpeed() > 0.0 && position.getSpeed() < filterOsmandSpeed)) {
                            filterStaticType.append("-minSpeed");
                        }
                    }
                }
            //} else {
                // filter all devices not in motion state and zero speed and greater than filterDistance values
                //if (position.getSpeed() == 0.0 && last.getSpeed() == 0.0 && distance > filterDistance) {
                //    filterStaticType.append("-noMotion");
                //}
            }
            if (!filterStaticType.isEmpty()) {
                LOGGER.info("Position filtered by StaticMovement{} from device: {}", filterStaticType, device.getUniqueId());
                return true;
            }
        }
        return false;
    }

    private boolean filterDistance(Position position, Position last) {
        long deviceId = position.getDeviceId();
        Device device = cacheManager.getObject(Device.class, deviceId);
        long distance = Math.round(position.getDouble(Position.KEY_DISTANCE));

        if (filterDistance != 0 && last != null) {
            if (position.getBoolean(Position.KEY_IGNITION) || last.getBoolean(Position.KEY_IGNITION)) {
                // LOGGER.info("Position NOT filtered by StaticMovement-onIgnition from device: {}", device.getUniqueId());
                return false;
            }
            if (position.getSpeed() < filterStaticThreshold && (distance != 0 && distance < filterDistance)) {
                LOGGER.info("Position filtered by Distance ({}m) from device: {}", distance, device.getUniqueId());
                return true;
            }
        }
        return false;
    }

    private boolean filterMaxSpeed(Position position, Position last) {
        if (filterMaxSpeed != 0 && last != null) {
            double distance = position.getDouble(Position.KEY_DISTANCE);
            double time = position.getFixTime().getTime() - last.getFixTime().getTime();
            return time > 0 && UnitsConverter.knotsFromMps(distance / (time / 1000)) > filterMaxSpeed;
        }
        return false;
    }

    private boolean filterMinPeriod(Position position, Position last) {
        if (filterMinPeriod != 0 && last != null) {
            long time = position.getFixTime().getTime() - last.getFixTime().getTime();
            return time > 0 && time < filterMinPeriod;
        }
        return false;
    }

    private boolean filterDailyLimit(Position position, Position last) {
        if (filterDailyLimit != 0
                && statisticsManager.messageStoredCount(position.getDeviceId()) >= filterDailyLimit) {
            long lastTime = last != null ? last.getFixTime().getTime() : 0;
            long interval = position.getFixTime().getTime() - lastTime;
            return filterDailyLimitInterval <= 0 || interval < filterDailyLimitInterval;
        }
        return false;
    }

    private boolean skipLimit(Position position, Position last) {
        if (skipLimit != 0 && last != null) {
            return (position.getServerTime().getTime() - last.getServerTime().getTime()) > skipLimit;
        }
        return false;
    }

    private boolean skipAttributes(Position position, Position last) {
        if (skipAttributes) {
            long deviceId = position.getDeviceId();
            Device device = cacheManager.getObject(Device.class, deviceId);
            String string = AttributeUtil.lookup(cacheManager, Keys.FILTER_SKIP_ATTRIBUTES, position.getDeviceId());
            for (String attribute : string.split("[ ,]")) {
                String current = position.getAttributes().get(attribute) != null ? position.getAttributes().get(attribute).toString().trim() : "empty";
                String previous = (last != null && last.getAttributes().get(attribute) != null) ? last.getAttributes().get(attribute).toString().trim() : "empty";

                // trim "power" attribute to 4 characters to reduce minor power fluctuations forcing updates
                if (attribute.equals("power")) {
                    if (current.length() > 4) {
                        current = current.substring(0,4);
                    }
                    if (previous.length() > 4) {
                        previous = previous.substring(0,4);
                    }
                }

                if ((!current.equals(previous)) && position.getAttributes().get(attribute) != null) {
                    // LOGGER.info("Position attribute {} updated from device: {}", attribute, device.getUniqueId());
                    LOGGER.info("Position attribute {} updated from device: {}, ({}->{})", attribute, device.getUniqueId(), previous, current);

                    return true;
                }
            }
        }
        return false;
    }

    protected boolean filter(Position position) {

        StringBuilder filterType = new StringBuilder();

        // filter out invalid data
        if (filterInvalid(position)) {
            filterType.append("Invalid ");
        }
        if (filterZero(position)) {
            filterType.append("Zero ");
        }
        if (filterOutdated(position)) {
            filterType.append("Outdated ");
        }
        if (filterFuture(position)) {
            filterType.append("Future ");
        }
        if (filterPast(position)) {
            filterType.append("Past ");
        }
        if (filterAccuracy(position)) {
            filterType.append("Accuracy ");
        }
        if (filterApproximate(position)) {
            filterType.append("Approximate ");
        }

        // filter out excessive data
        long deviceId = position.getDeviceId();
        Position preceding = null;
        if (filterDuplicate || filterStatic
                || filterDistance > 0 || filterMaxSpeed > 0 || filterMinPeriod > 0 || filterDailyLimit > 0) {
            if (filterRelative) {
                try {
                    Date newFixTime = position.getFixTime();
                    preceding = getPrecedingPosition(deviceId, newFixTime);
                } catch (StorageException e) {
                    LOGGER.warn("Error retrieving preceding position; fall backing to last received position.", e);
                    preceding = cacheManager.getPosition(deviceId);
                }
            } else {
                preceding = cacheManager.getPosition(deviceId);
            }
            if (filterDuplicate(position, preceding) && !skipLimit(position, preceding)) {
                filterType.append("Duplicate ");
            }
            if (filterDistance(position, preceding) && !skipLimit(position, preceding) && !filterDuplicate(position, preceding) && !filterStatic(position, preceding, false)) {
                filterType.append("Distance ");
            }
            if (filterStatic(position, preceding, true) && !skipLimit(position, preceding)) {
                filterType.append("Static ");
            }
            if (filterStaticMove(position, preceding) && !skipLimit(position, preceding)) {
                filterType.append("StaticMovement ");
            }
            if (filterMaxSpeed(position, preceding)) {
                filterType.append("MaxSpeed ");
            }
            if (filterMinPeriod(position, preceding)) {
                filterType.append("MinPeriod ");
            }
            if (filterDailyLimit(position, preceding)) {
                filterType.append("DailyLimit ");
            }
        }

        Device device = cacheManager.getObject(Device.class, deviceId);
        if (device.getCalendarId() > 0) {
            Calendar calendar = cacheManager.getObject(Calendar.class, device.getCalendarId());
            if (!calendar.checkMoment(position.getFixTime())) {
                filterType.append("Calendar ");
            }
        }

        if (!filterType.isEmpty()) {
            if (skipAttributes(position, preceding)  && !filterMaxSpeed(position, preceding) && !filterMinPeriod(position, preceding) && !filterDailyLimit(position, preceding)) {
                position.setLatitude(cacheManager.getPosition(deviceId).getLatitude());
                position.setLongitude(cacheManager.getPosition(deviceId).getLongitude());
                LOGGER.info("Position NOT filtered by {}filters from device: {}", filterType, device.getUniqueId());
            } else {
                LOGGER.info("Position filtered by {}filters from device: {}", filterType, device.getUniqueId());
                return true;
            }
        }
        return false;
    }

    @Override
    public void onPosition(Position position, Callback callback) {
        callback.processed(filter(position));
    }

}
