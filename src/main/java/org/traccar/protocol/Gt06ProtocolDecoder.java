/*
 * Copyright 2012 - 2024 Anton Tananaev (anton@traccar.org)
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
package org.traccar.protocol;

import com.google.api.client.util.DateTime;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import org.traccar.BaseProtocolDecoder;
import org.traccar.helper.BufferUtil;
import org.traccar.session.DeviceSession;
import org.traccar.NetworkMessage;
import org.traccar.Protocol;
import org.traccar.helper.BcdUtil;
import org.traccar.helper.BitUtil;
import org.traccar.helper.Checksum;
import org.traccar.helper.DateBuilder;
import org.traccar.helper.Parser;
import org.traccar.helper.PatternBuilder;
import org.traccar.helper.UnitsConverter;
import org.traccar.model.CellTower;
import org.traccar.model.Network;
import org.traccar.model.Position;
import org.traccar.model.WifiAccessPoint;

import java.net.SocketAddress;
import java.nio.Buffer;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Pattern;

public class Gt06ProtocolDecoder extends BaseProtocolDecoder {

    private final Map<Integer, ByteBuf> photos = new HashMap<>();

    public Gt06ProtocolDecoder(Protocol protocol) {
        super(protocol);
    }

    public static final int MSG_LOGIN = 0x01;
    public static final int MSG_HEARTBEAT = 0x13;
    public static final int MSG_STRING_INFO = 0x21;
    public static final int MSG_COMMAND_0 = 0x80;
    public static final int MSG_COMMAND_1 = 0x81;
    public static final int MSG_COMMAND_2 = 0x82;
    public static final int MSG_TIME_REQUEST = 0x8A;
    public static final int MSG_INFO = 0x94;
    public static final int MSG_SERIAL = 0x9B;
    public static final int MSG_GPS_LBS = 0xA0;
    public static final int MSG_FENCE_MULTI = 0xA4;
    public static final int MSG_LBS_ALARM = 0xA5;

    private enum Variant {
        STANDARD,
    }

    private Variant variant;

    private static final Pattern PATTERN_LOCATION = new PatternBuilder()
            .text("Current position!")
            .number("Lat:([NS])(d+.d+),")        // latitude
            .number("Lon:([EW])(d+.d+),")        // longitude
            .text("Course:").number("(d+.d+),")  // course
            .text("Speed:").number("(d+.d+),")   // speed
            .text("DateTime:")
            .number("(dddd)-(dd)-(dd) +")        // date
            .number("(dd):(dd):(dd)")            // time
            .compile();

    private boolean isSupported(int type, String model) {
        return hasGps(type) || hasLbs(type) || hasStatus(type, model);
    }

    private boolean hasGps(int type) {
        switch (type) {
            case MSG_GPS_LBS:
            case MSG_FENCE_MULTI:
                return true;
            default:
                return false;
        }
    }

    private boolean hasLbs(int type) {
        switch (type) {
            case MSG_GPS_LBS:
            case MSG_FENCE_MULTI:
            case MSG_LBS_ALARM:
                return true;
            default:
                return false;
        }
    }

    private boolean hasStatus(int type, String model) {
        switch (type) {
            case MSG_FENCE_MULTI:
            case MSG_LBS_ALARM:
                return true;
            default:
                return false;
        }
    }

    private void sendResponse(Channel channel, boolean extended, int type, int index, ByteBuf content) {
        if (channel != null) {
            ByteBuf response = Unpooled.buffer();
            int length = 5 + (content != null ? content.readableBytes() : 0);
            if (extended) {
                response.writeShort(0x7979);
                response.writeShort(length);
            } else {
                response.writeShort(0x7878);
                response.writeByte(length);
            }
            response.writeByte(type);
            if (content != null) {
                response.writeBytes(content);
                content.release();
            }
            response.writeShort(index);
            response.writeShort(Checksum.crc16(Checksum.CRC16_X25,
                    response.nioBuffer(2, response.writerIndex() - 2)));
            response.writeByte('\r');
            response.writeByte('\n');
            channel.writeAndFlush(new NetworkMessage(response, channel.remoteAddress()));
        }
    }

    public static boolean decodeGps(Position position, ByteBuf buf, boolean hasLength, TimeZone timezone) {
        return decodeGps(position, buf, hasLength, true, true, false, false, timezone);
    }

    public static boolean decodeGps(
            Position position, ByteBuf buf, boolean hasLength, boolean hasSatellites,
            boolean hasSpeed, boolean longSpeed, boolean swapFlags, TimeZone timezone) {

        DateBuilder dateBuilder = new DateBuilder(timezone)
                .setDate(buf.readUnsignedByte(), buf.readUnsignedByte(), buf.readUnsignedByte())
                .setTime(buf.readUnsignedByte(), buf.readUnsignedByte(), buf.readUnsignedByte());
        position.setTime(dateBuilder.getDate());

        if (hasLength && buf.readUnsignedByte() == 0) {
            return false;
        }

        if (hasSatellites) {
            position.set(Position.KEY_SATELLITES, BitUtil.to(buf.readUnsignedByte(), 4));
        }

        double latitude = buf.readUnsignedInt() / 60.0 / 30000.0;
        double longitude = buf.readUnsignedInt() / 60.0 / 30000.0;

        int flags = 0;
        if (swapFlags) {
            flags = buf.readUnsignedShort();
        }
        if (hasSpeed) {
            position.setSpeed(UnitsConverter.knotsFromKph(
                    longSpeed ? buf.readUnsignedShort() : buf.readUnsignedByte()));
        }
        if (!swapFlags) {
            flags = buf.readUnsignedShort();
        }

        position.setCourse(BitUtil.to(flags, 10));
        position.setValid(BitUtil.check(flags, 12));

        if (!BitUtil.check(flags, 10)) {
            latitude = -latitude;
        }
        if (BitUtil.check(flags, 11)) {
            longitude = -longitude;
        }

        position.setLatitude(latitude);
        position.setLongitude(longitude);

        return true;
    }

    private boolean decodeLbs(Position position, ByteBuf buf, int type, boolean hasLength) {

        int length = 0;
        if (hasLength) {
            length = buf.readUnsignedByte();
            if (length == 0) {
                boolean zeroedData = true;
                for (int i = buf.readerIndex() + 9; i < buf.readerIndex() + 45 && i < buf.writerIndex(); i++) {
                    if (buf.getByte(i) != 0) {
                        zeroedData = false;
                        break;
                    }
                }
                if (zeroedData) {
                    buf.skipBytes(Math.min(buf.readableBytes(), 45));
                }
                return false;
            }
        }

        int mcc = buf.readUnsignedShort();
        int mnc; // 2bytes
        if (BitUtil.check(mcc, 15)) {
            mnc = buf.readUnsignedShort();
        } else { // 1byte
            mnc = buf.readUnsignedByte();
        }
        int lac; // 4bytes
        if (type == MSG_LBS_ALARM || type == MSG_GPS_LBS) {
            lac = buf.readInt();
        } else {
            lac = buf.readUnsignedShort();
        }
        long cid; // 8bytes
        if (type == MSG_LBS_ALARM || type == MSG_GPS_LBS) {
            cid = buf.readLong();
        } else {
            cid = buf.readUnsignedMedium();
        }

        position.setNetwork(new Network(CellTower.from(BitUtil.to(mcc, 15), mnc, lac, cid)));

        if (type == MSG_GPS_LBS) {
            position.set(Position.KEY_IGNITION, buf.readUnsignedByte() > 0);
            int upload = buf.readUnsignedByte();
            position.set("uploadType", upload);
            if (upload == 0x08) {
                position.addAlarm(Position.ALARM_POWER_ON);
            }
            position.set("realtime", (buf.readUnsignedByte() <= 0));
        } else {
            if (length > 9) {
                buf.skipBytes(length - 9);
            }
        }

        return true;
    }

    private void decodeStatus(Position position, ByteBuf buf) {

        int status = buf.readUnsignedByte();

        position.set(Position.KEY_STATUS, status);
        position.set(Position.KEY_ARMED, BitUtil.check(status, 0));
        position.set(Position.KEY_IGNITION, BitUtil.check(status, 1));
        position.set(Position.KEY_CHARGE, BitUtil.check(status, 2));
        position.set("gpsPower", BitUtil.check(status, 6));
        position.set(Position.KEY_BLOCKED, BitUtil.check(status, 7));

        switch (BitUtil.between(status, 3, 6)) {
            case 1:
                position.addAlarm(Position.ALARM_VIBRATION);
                break;
            case 2:
                position.addAlarm(Position.ALARM_POWER_CUT);
                position.set(Position.KEY_POWER, 0.0);
                break;
            case 3:
                position.addAlarm(Position.ALARM_LOW_BATTERY);
                break;
            case 4:
                position.addAlarm(Position.ALARM_SOS);
                break;
            case 6:
                position.addAlarm(Position.ALARM_GEOFENCE);
                break;
            case 7:
                position.addAlarm(Position.ALARM_REMOVING);
                break;
            default:
                break;
        }
    }

    private void decodeAlarmOther(Position position, ByteBuf alarm, boolean modelVl) {
        switch (alarm.readUnsignedByte()) {
            case 0x01:
                position.addAlarm(Position.ALARM_SOS);
                break;
            case 0x02:
                position.addAlarm(Position.ALARM_POWER_CUT);
                position.set(Position.KEY_POWER, 0.0);
                break;
            case 0x03:
                position.addAlarm(Position.ALARM_VIBRATION);
                break;
            case 0x04:
                position.addAlarm(Position.ALARM_GEOFENCE_ENTER);
                break;
            case 0x05:
                position.addAlarm(Position.ALARM_GEOFENCE_EXIT);
                break;
            case 0x06:
                position.addAlarm(Position.ALARM_OVERSPEED);
                break;
            case 0x09:
                position.addAlarm(Position.ALARM_TOW);
                break;
            case 0x0E, 0x0F, 0x19:
                position.addAlarm(Position.ALARM_LOW_BATTERY);
                break;
            case 0x11, 0x15:
                position.addAlarm(Position.ALARM_POWER_OFF);
                break;
            case 0x0C, 0x13, 0x25:
                position.addAlarm(Position.ALARM_TAMPERING);
                break;
            case 0x14:
                position.addAlarm(Position.ALARM_DOOR);
                break;
            case 0x18:
                position.addAlarm(Position.ALARM_REMOVING);
                break;
            case 0x1A, 0x27, 0x30:
                position.addAlarm(Position.ALARM_BRAKING);
                break;
            case 0x1B, 0x2A, 0x2B, 0x2E:
                position.addAlarm(Position.ALARM_CORNERING);
                break;
            case 0x23:
                position.addAlarm(Position.ALARM_FALL_DOWN);
                break;
            case 0x26:
                position.addAlarm(Position.ALARM_ACCELERATION);
                break;
            case 0x2C:
                position.addAlarm(Position.ALARM_ACCIDENT);
                break;
            default:
                break;
        };
    }

    private String decodeAlarm(short value, boolean modelVL) {
        return switch (value) {
            case 0x01 -> Position.ALARM_SOS;
            case 0x02 -> Position.ALARM_POWER_CUT;
            case 0x03 -> Position.ALARM_VIBRATION;
            case 0x04 -> Position.ALARM_GEOFENCE_ENTER;
            case 0x05 -> Position.ALARM_GEOFENCE_EXIT;
            case 0x06 -> Position.ALARM_OVERSPEED;
            case 0x09 -> Position.ALARM_TOW;
            case 0x0E, 0x0F, 0x19 -> Position.ALARM_LOW_BATTERY;
            case 0x11, 0x15 -> Position.ALARM_POWER_OFF;
            case 0x0C, 0x13, 0x25 -> Position.ALARM_TAMPERING;
            case 0x14 -> Position.ALARM_DOOR;
            case 0x18 -> Position.ALARM_REMOVING;
            case 0x1A, 0x27, 0x30 -> Position.ALARM_BRAKING;
            case 0x1B, 0x2A, 0x2B, 0x2E -> Position.ALARM_CORNERING;
            case 0x23 -> Position.ALARM_FALL_DOWN;
            case 0x26 -> Position.ALARM_ACCELERATION;
            case 0x2C -> Position.ALARM_ACCIDENT;
            default -> null;
        };
    }

    private Object decodeBasic(Channel channel, SocketAddress remoteAddress, ByteBuf buf) {

        int length = buf.readUnsignedByte();
        int dataLength = length - 5;
        int type = buf.readUnsignedByte();

        Position position = new Position(getProtocolName());
        DeviceSession deviceSession = null;
        if (type != MSG_LOGIN) {
            deviceSession = getDeviceSession(channel, remoteAddress);
            if (deviceSession == null) {
                return null;
            }
            position.setDeviceId(deviceSession.getDeviceId());
            if (!deviceSession.contains(DeviceSession.KEY_TIMEZONE)) {
                deviceSession.set(DeviceSession.KEY_TIMEZONE, getTimeZone(deviceSession.getDeviceId()));
            }
        }

        String model = deviceSession != null ? getDeviceModel(deviceSession) : null;
        boolean modelVL = model != null && Set.of("VL103", "VL110", "VL111", "LL303", "VL512").contains(model);

        if (type == MSG_LOGIN) {

            String imei = ByteBufUtil.hexDump(buf.readSlice(8)).substring(1);
            buf.readUnsignedShort(); // type

            deviceSession = getDeviceSession(channel, remoteAddress, imei);
            if (deviceSession != null) {
                TimeZone timeZone = getTimeZone(deviceSession.getDeviceId(), null);
                if (timeZone == null && dataLength > 10) {
                    int extensionBits = buf.readUnsignedShort();
                    int hours = (extensionBits >> 4) / 100;
                    int minutes = (extensionBits >> 4) % 100;
                    int offset = (hours * 60 + minutes) * 60;
                    if ((extensionBits & 0x8) != 0) {
                        offset = -offset;
                    }
                    timeZone = TimeZone.getTimeZone("UTC");
                    timeZone.setRawOffset(offset * 1000);
                }
                deviceSession.set(DeviceSession.KEY_TIMEZONE, timeZone);

                sendResponse(channel, false, type, buf.getShort(buf.writerIndex() - 6), null);
            }

            return null;

        } else if (type == MSG_HEARTBEAT) {

            getLastLocation(position, null);

            position.set(Position.KEY_TYPE, type);

            decodeStatus(position, buf);

            // int status = buf.readUnsignedByte();
            // position.set(Position.KEY_ARMED, BitUtil.check(status, 0));
            // position.set(Position.KEY_IGNITION, BitUtil.check(status, 1));
            // position.set(Position.KEY_CHARGE, BitUtil.check(status, 2));
            // position.set("gpsPower", BitUtil.check(status, 6));
            // position.set(Position.KEY_BLOCKED, BitUtil.check(status, 7));

            int battery = buf.readUnsignedByte();
            if (battery <= 6) {
                position.set(Position.KEY_BATTERY_LEVEL, battery * 100 / 6);
            } else if (battery <= 100) {
                position.set(Position.KEY_BATTERY_LEVEL, battery);
            }
            if (buf.readableBytes() >= 1 + 6) {
                position.set(Position.KEY_RSSI, buf.readUnsignedByte() + 1);
            }

            sendResponse(channel, false, type, buf.getShort(buf.writerIndex() - 6), null);

            return position;

        } else if (type == MSG_TIME_REQUEST) {

            Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            ByteBuf content = Unpooled.buffer();
            content.writeByte(calendar.get(Calendar.YEAR) - 2000);
            content.writeByte(calendar.get(Calendar.MONTH) + 1);
            content.writeByte(calendar.get(Calendar.DAY_OF_MONTH));
            content.writeByte(calendar.get(Calendar.HOUR_OF_DAY));
            content.writeByte(calendar.get(Calendar.MINUTE));
            content.writeByte(calendar.get(Calendar.SECOND));
            sendResponse(channel, false, MSG_TIME_REQUEST, 0, content);

            return null;

        } else if (isSupported(type, model)) {

            position.set(Position.KEY_TYPE, type);

            if (hasGps(type)) {
                decodeGps(position, buf, false, deviceSession.get(DeviceSession.KEY_TIMEZONE));
            } else {
                getLastLocation(position, null);
            }

            if (hasLbs(type) && buf.readableBytes() > 6) {
                boolean hasLength = hasStatus(type, model)
                        && type != MSG_LBS_ALARM;
                decodeLbs(position, buf, type, hasLength);
            }

            if (hasStatus(type, model)) {
                decodeStatus(position, buf);
                int battery = buf.readUnsignedByte();
                if (battery <= 6) {
                    position.set(Position.KEY_BATTERY_LEVEL, battery * 100 / 6);
                } else if (battery <= 100) {
                    position.set(Position.KEY_BATTERY_LEVEL, battery);
                }
                position.set(Position.KEY_RSSI, buf.readUnsignedByte() + 1);
                // short extension = buf.readUnsignedByte();
                // position.addAlarm(decodeAlarm(extension, modelVL));
                decodeAlarmOther(position, buf, modelVL);

            }

            if (type == MSG_FENCE_MULTI) {
                position.set(Position.KEY_GEOFENCE, buf.readUnsignedByte());
            }

        } else {

            if (dataLength > 0) {
                buf.skipBytes(dataLength);
            }
            if (type != MSG_COMMAND_0 && type != MSG_COMMAND_1 && type != MSG_COMMAND_2) {
                sendResponse(channel, false, type, buf.getShort(buf.writerIndex() - 6), null);
            }
            return null;

        }

        sendResponse(channel, false, type, buf.getShort(buf.writerIndex() - 6), null);

        return position;
    }

    private static Date decodeDate(ByteBuf buf, DeviceSession deviceSession) {
        DateBuilder dateBuilder = new DateBuilder((TimeZone) deviceSession.get(DeviceSession.KEY_TIMEZONE))
                .setDate(buf.readUnsignedByte(), buf.readUnsignedByte(), buf.readUnsignedByte())
                .setTime(buf.readUnsignedByte(), buf.readUnsignedByte(), buf.readUnsignedByte());
        return dateBuilder.getDate();
    }

    private Object decodeExtended(Channel channel, SocketAddress remoteAddress, ByteBuf buf) {

        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress);
        if (deviceSession == null) {
            return null;
        }

        if (!deviceSession.contains(DeviceSession.KEY_TIMEZONE)) {
            deviceSession.set(DeviceSession.KEY_TIMEZONE, getTimeZone(deviceSession.getDeviceId()));
        }

        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());

        int length = buf.readUnsignedShort();
        int type = buf.readUnsignedByte();

        position.set(Position.KEY_TYPE, type);

        if (type == MSG_STRING_INFO) {

            buf.readUnsignedInt(); // server flag
            String data;
            if (buf.readUnsignedByte() == 1) {
                data = buf.readSlice(buf.readableBytes() - 6).toString(StandardCharsets.US_ASCII);
            } else {
                data = buf.readSlice(buf.readableBytes() - 6).toString(StandardCharsets.UTF_16BE);
            }

            Parser parser = new Parser(PATTERN_LOCATION, data);

            if (parser.matches()) {
                position.setValid(true);
                position.setLatitude(parser.nextCoordinate(Parser.CoordinateFormat.HEM_DEG));
                position.setLongitude(parser.nextCoordinate(Parser.CoordinateFormat.HEM_DEG));
                position.setCourse(parser.nextDouble());
                position.setSpeed(parser.nextDouble());
                position.setTime(parser.nextDateTime(Parser.DateTimeFormat.YMD_HMS));
            } else {
                getLastLocation(position, null);
                position.set(Position.KEY_RESULT, data);
            }

            return position;

        } else if (type == MSG_INFO) {

            int subType = buf.readUnsignedByte();

            getLastLocation(position, null);

            if (subType == 0x00) {

                position.set(Position.KEY_POWER, buf.readUnsignedShort() * 0.01);
                return position;

            } else if (subType == 0x04) {

                CharSequence content = buf.readCharSequence(buf.readableBytes() - 4 - 2, StandardCharsets.US_ASCII);
                String[] values = content.toString().split(";");
                for (String value : values) {
                    String[] pair = value.split("=");
                    switch (pair[0]) {
                        case "ALM1":
                        case "ALM2":
                        case "ALM3":
                            position.set("alarm" + pair[0].charAt(3) + "Status", Integer.parseInt(pair[1], 16));
                        case "STA1":
                            position.set("otherStatus", Integer.parseInt(pair[1], 16));
                            break;
                        case "DYD":
                            position.set("engineStatus", Integer.parseInt(pair[1], 16));
                            break;
                        default:
                            break;
                    }
                }
                return position;

            } else if (subType == 0x05) {

                if (buf.readableBytes() >= 6 + 1 + 6) {
                    position.setDeviceTime(decodeDate(buf, deviceSession));
                }

                int flags = buf.readUnsignedByte();
                position.set(Position.KEY_DOOR, BitUtil.check(flags, 0));
                position.set(Position.PREFIX_IO + 1, BitUtil.check(flags, 2));
                return position;
            }

        } else if (type == MSG_SERIAL) {

            getLastLocation(position, null);

            buf.readUnsignedByte(); // external device type code

            ByteBuf data = buf.readSlice(buf.readableBytes() - 6); // index + checksum + footer
            if (BufferUtil.isPrintable(data, data.readableBytes())) {
                String value = data.readCharSequence(data.readableBytes(), StandardCharsets.US_ASCII).toString();
                position.set(Position.KEY_RESULT, value.trim());
            } else {
                position.set(Position.KEY_RESULT, ByteBufUtil.hexDump(data));
            }

            return position;

        }

        return null;
    }

    private void decodeVariant(ByteBuf buf) {
        int header = buf.getUnsignedShort(buf.readerIndex());
        int length;
        int type;
        if (header == 0x7878) {
            length = buf.getUnsignedByte(buf.readerIndex() + 2);
            type = buf.getUnsignedByte(buf.readerIndex() + 2 + 1);
        } else {
            length = buf.getUnsignedShort(buf.readerIndex() + 2);
            type = buf.getUnsignedByte(buf.readerIndex() + 2 + 2);
        }
            variant = Variant.STANDARD;
    }

    @Override
    protected Object decode(
            Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {

        ByteBuf buf = (ByteBuf) msg;

        decodeVariant(buf);

        int header = buf.readShort();

        if (header == 0x7878) {
            return decodeBasic(channel, remoteAddress, buf);
        } else {
            return decodeExtended(channel, remoteAddress, buf);
        }
    }

}
