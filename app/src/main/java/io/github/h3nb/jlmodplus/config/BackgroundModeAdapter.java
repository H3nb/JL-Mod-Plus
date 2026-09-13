/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.config;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;

/** Field-local Gson adapter: malformed mode data falls back without rejecting the profile. */
public final class BackgroundModeAdapter extends TypeAdapter<Integer> {
    @Override
    public Integer read(JsonReader reader) throws IOException {
        JsonToken token = reader.peek();
        if (token == JsonToken.NUMBER) {
            String value = reader.nextString();
            if (!value.matches("-?\\d+")) return BackgroundMode.CUSTOM;
            try {
                return BackgroundMode.sanitize(Integer.parseInt(value));
            } catch (NumberFormatException ignored) {
                return BackgroundMode.CUSTOM;
            }
        }
        if (token == JsonToken.NULL) {
            reader.nextNull();
        } else {
            reader.skipValue();
        }
        return BackgroundMode.CUSTOM;
    }

    @Override
    public void write(JsonWriter writer, Integer value) throws IOException {
        writer.value(BackgroundMode.sanitize(value == null ? BackgroundMode.CUSTOM : value));
    }
}
