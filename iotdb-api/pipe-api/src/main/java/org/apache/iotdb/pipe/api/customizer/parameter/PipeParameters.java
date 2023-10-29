/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.pipe.api.customizer.parameter;

import org.apache.iotdb.pipe.api.PipeConnector;
import org.apache.iotdb.pipe.api.PipeProcessor;
import org.apache.iotdb.pipe.api.customizer.configuration.PipeConnectorRuntimeConfiguration;
import org.apache.iotdb.pipe.api.customizer.configuration.PipeProcessorRuntimeConfiguration;

import java.util.List;
import java.util.Map;

/**
 * Used in {@link PipeProcessor#customize(PipeParameters, PipeProcessorRuntimeConfiguration)} and
 * {@link PipeConnector#customize(PipeParameters, PipeConnectorRuntimeConfiguration)}.
 *
 * <p>This class is used to parse the parameters in WITH PROCESSOR and WITH CONNECTOR when creating
 * a pipe.
 *
 * <p>The input parameters is the key-value pair attributes for customization.
 */
public class PipeParameters {

  private final Map<String, String> attributes;

  public PipeParameters(Map<String, String> attributes) {
    this.attributes = attributes;
  }

  public Map<String, String> getAttribute() {
    return attributes;
  }

  public boolean hasAttribute(String key) {
    return attributes.containsKey(key);
  }

  public boolean hasAnyAttributes(String... keys) {
    for (final String key : keys) {
      if (attributes.containsKey(key)) {
        return true;
      }
    }
    return false;
  }

  public String getString(String... keys) {
    for (final String key : keys) {
      final String value = attributes.get(key);
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  public Boolean getBoolean(String... keys) {
    for (final String key : keys) {
      final String value = attributes.get(key);
      if (value != null) {
        return Boolean.parseBoolean(value);
      }
    }
    return null;
  }

  public Integer getInt(String... keys) {
    for (final String key : keys) {
      final String value = attributes.get(key);
      if (value != null) {
        return Integer.parseInt(value);
      }
    }
    return null;
  }

  public Long getLong(String... keys) {
    for (final String key : keys) {
      final String value = attributes.get(key);
      if (value != null) {
        return Long.parseLong(value);
      }
    }
    return null;
  }

  public Float getFloat(String... keys) {
    for (final String key : keys) {
      final String value = attributes.get(key);
      if (value != null) {
        return Float.parseFloat(value);
      }
    }
    return null;
  }

  public Double getDouble(String... keys) {
    for (final String key : keys) {
      final String value = attributes.get(key);
      if (value != null) {
        return Double.parseDouble(value);
      }
    }
    return null;
  }

  public String getStringOrDefault(String key, String defaultValue) {
    String value = attributes.get(key);
    return value == null ? defaultValue : value;
  }

  public boolean getBooleanOrDefault(String key, boolean defaultValue) {
    String value = attributes.get(key);
    return value == null ? defaultValue : Boolean.parseBoolean(value);
  }

  public int getIntOrDefault(String key, int defaultValue) {
    String value = attributes.get(key);
    return value == null ? defaultValue : Integer.parseInt(value);
  }

  public long getLongOrDefault(String key, long defaultValue) {
    String value = attributes.get(key);
    return value == null ? defaultValue : Long.parseLong(value);
  }

  public float getFloatOrDefault(String key, float defaultValue) {
    String value = attributes.get(key);
    return value == null ? defaultValue : Float.parseFloat(value);
  }

  public double getDoubleOrDefault(String key, double defaultValue) {
    String value = attributes.get(key);
    return value == null ? defaultValue : Double.parseDouble(value);
  }

  public String getStringOrDefault(List<String> keys, String defaultValue) {
    for (final String key : keys) {
      final String value = attributes.get(key);
      if (value != null) {
        return value;
      }
    }
    return defaultValue;
  }

  public boolean getBooleanOrDefault(List<String> keys, boolean defaultValue) {
    for (final String key : keys) {
      final String value = attributes.get(key);
      if (value != null) {
        return Boolean.parseBoolean(value);
      }
    }
    return defaultValue;
  }

  public int getIntOrDefault(List<String> keys, int defaultValue) {
    for (final String key : keys) {
      final String value = attributes.get(key);
      if (value != null) {
        return Integer.parseInt(value);
      }
    }
    return defaultValue;
  }

  public long getLongOrDefault(List<String> keys, long defaultValue) {
    for (final String key : keys) {
      final String value = attributes.get(key);
      if (value != null) {
        return Long.parseLong(value);
      }
    }
    return defaultValue;
  }

  public float getFloatOrDefault(List<String> keys, float defaultValue) {
    for (final String key : keys) {
      final String value = attributes.get(key);
      if (value != null) {
        return Float.parseFloat(value);
      }
    }
    return defaultValue;
  }

  public double getDoubleOrDefault(List<String> keys, double defaultValue) {
    for (final String key : keys) {
      final String value = attributes.get(key);
      if (value != null) {
        return Double.parseDouble(value);
      }
    }
    return defaultValue;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null || getClass() != obj.getClass()) {
      return false;
    }
    PipeParameters that = (PipeParameters) obj;
    return attributes.equals(that.attributes);
  }

  @Override
  public int hashCode() {
    return attributes.hashCode();
  }

  @Override
  public String toString() {
    return attributes.toString();
  }
}
