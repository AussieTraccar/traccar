/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
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
package org.traccar.reports;

import org.jxls.util.JxlsHelper;
import org.traccar.config.Config;
import org.traccar.config.Keys;

import org.traccar.helper.model.DeviceUtil;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.reports.common.ReportUtils;
import org.traccar.reports.model.GeofenceReportItem;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Paths;

import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;

public class GeofenceReportProvider {

    private final Config config;
    private final ReportUtils reportUtils;
    private final Storage storage;

    @Inject
    public GeofenceReportProvider(Config config, ReportUtils reportUtils, Storage storage) {
        this.config = config;
        this.reportUtils = reportUtils;
        this.storage = storage;
    }

    private Collection<Event> getEvents(long deviceId, Date from, Date to) throws StorageException {
        return storage.getObjects(Event.class, new Request(
                new Columns.All(),
                new Condition.And(
                        new Condition.Equals("deviceId", deviceId),
                        new Condition.And(
                                new Condition.Between("eventTime", "from", from, "to", to),
                                new Condition.Or(
                                        new Condition.Equals("type", Event.TYPE_GEOFENCE_ENTER),
                                        new Condition.Equals("type", Event.TYPE_GEOFENCE_EXIT)))),
                new Order("eventTime")));
    }

    public Collection<GeofenceReportItem> getObjects(
            long userId, Collection<Long> deviceIds, Collection<Long> groupIds, Collection<Long> geofenceIds,
            Date from, Date to) throws StorageException {
        reportUtils.checkPeriodLimit(from, to);

        var result = new ArrayList<GeofenceReportItem>();
        for (Device device : DeviceUtil.getAccessibleDevices(storage, userId, deviceIds, groupIds)) {
            var openEvents = new HashMap<Long, Event>();
            for (Event event : getEvents(device.getId(), from, to)) {
                long geofenceId = event.getGeofenceId();
                if (geofenceIds.contains(geofenceId)) {
                    if (Event.TYPE_GEOFENCE_ENTER.equals(event.getType())) {
                        openEvents.put(geofenceId, event);
                    } else if (Event.TYPE_GEOFENCE_EXIT.equals(event.getType())) {
                        Event enterEvent = openEvents.remove(geofenceId);
                        if (enterEvent != null && !event.getEventTime().before(enterEvent.getEventTime())) {
                            GeofenceReportItem item = new GeofenceReportItem();
                            item.setDeviceId(device.getId());
                            item.setDeviceName(device.getName());
                            item.setGeofenceId(geofenceId);
                            item.setStartTime(enterEvent.getEventTime());
                            item.setEndTime(event.getEventTime());
                            result.add(item);
                        }
                    }
                }
            }
        }
        result.sort(Comparator.comparing(GeofenceReportItem::getStartTime));
        return result;
    }

    public void getExcel(OutputStream outputStream,
                         long userId, Collection<Long> deviceIds, Collection<Long> groupIds, Collection<Long> geofenceIds,
                         Date from, Date to) throws StorageException, IOException {
        Collection<GeofenceReportItem> summaries = getObjects(userId, deviceIds, groupIds, geofenceIds, from, to);

        File file = Paths.get(config.getString(Keys.TEMPLATES_ROOT), "export", "geofence.xlsx").toFile();
        try (InputStream inputStream = new FileInputStream(file)) {
            var context = reportUtils.initializeContext(userId);
            context.putVar("geofenceIds", geofenceIds);
            context.putVar("from", from);
            context.putVar("to", to);
            JxlsHelper.getInstance().setUseFastFormulaProcessor(false)
                    .processTemplate(inputStream, outputStream, context);
        }
    }

}
