/*
 * Copyright 2024 - 2025 Anton Tananaev (anton@traccar.org)
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
package org.traccar.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PortConfigSuffix extends ConfigSuffix<Integer> {

    private static final Map<String, Integer> PORTS = new HashMap<>();

    static {
        PORTS.put("freematics", 5170);
        PORTS.put("gt06", 5023);
        PORTS.put("h02", 5013);
        PORTS.put("jt600", 5014);
        PORTS.put("huabao", 5015);
        PORTS.put("osmand", 5055);
        PORTS.put("teltonika", 5027);
      }

    PortConfigSuffix(String key, List<KeyType> types) {
        super(key, types, null);
    }

    @Override
    public ConfigKey<Integer> withPrefix(String protocol) {
        return new IntegerConfigKey(protocol + keySuffix, types, PORTS.get(protocol));
    }
}
