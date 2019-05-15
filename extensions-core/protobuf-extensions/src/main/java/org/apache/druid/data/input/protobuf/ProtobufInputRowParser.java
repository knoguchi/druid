/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.data.input.protobuf;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.os72.protobuf.dynamic.DynamicSchema;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import org.apache.druid.data.input.ByteBufferInputRowParser;
import org.apache.druid.data.input.InputRow;
import org.apache.druid.data.input.MapBasedInputRow;
import org.apache.druid.data.input.impl.ParseSpec;
import org.apache.druid.java.util.common.IAE;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.parsers.ParseException;
import org.apache.druid.java.util.common.parsers.Parser;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ProtobufInputRowParser implements ByteBufferInputRowParser
{
  private final ParseSpec parseSpec;
  private final String descriptorFilePath;
  private final String protoMessageType;
  private final Boolean isLengthDelimited;
  private Descriptor descriptor;
  private Parser<String, Object> parser;
  private final List<String> dimensions;

  @JsonCreator
  public ProtobufInputRowParser(
      @JsonProperty("parseSpec") ParseSpec parseSpec,
      @JsonProperty("descriptor") String descriptorFilePath,
      @JsonProperty("protoMessageType") String protoMessageType,
      @JsonProperty("isLengthDelimited") Boolean isLengthDelimited
  )
  {
    this.parseSpec = parseSpec;
    this.descriptorFilePath = descriptorFilePath;
    this.protoMessageType = protoMessageType;
    this.isLengthDelimited = isLengthDelimited;
    this.dimensions = parseSpec.getDimensionsSpec().getDimensionNames();
  }

  @Override
  public ParseSpec getParseSpec()
  {
    return parseSpec;
  }

  @Override
  public ProtobufInputRowParser withParseSpec(ParseSpec parseSpec)
  {
    return new ProtobufInputRowParser(parseSpec, descriptorFilePath, protoMessageType, isLengthDelimited);
  }

  @VisibleForTesting
  void initDescriptor()
  {
    if (this.descriptor == null) {
      this.descriptor = getDescriptor(descriptorFilePath);
    }
  }

  private InputRow parseInput(ByteBuffer input)
  {
    String json;
    try {
      DynamicMessage message = DynamicMessage.parseFrom(descriptor, ByteString.copyFrom(input));
      json = JsonFormat.printer().print(message);
    }
    catch (InvalidProtocolBufferException e) {
      throw new ParseException(e, "Protobuf message could not be parsed");
    }
    return parseJson(json);
  }

  private InputRow parseInput(ByteBuffer input, int size)
  {
    String json;
    try {
      DynamicMessage message = DynamicMessage.parseFrom(descriptor, ByteString.copyFrom(input, size));
      json = JsonFormat.printer().print(message);
    }
    catch (InvalidProtocolBufferException e) {
      throw new ParseException(e, "Protobuf message could not be parsed");
    }
    return parseJson(json);
  }

  private InputRow parseJson(String json)
  {
    Map<String, Object> record = parser.parseToMap(json);
    final List<String> dimensions;
    if (!this.dimensions.isEmpty()) {
      dimensions = this.dimensions;
    } else {
      dimensions = Lists.newArrayList(
          Sets.difference(record.keySet(), parseSpec.getDimensionsSpec().getDimensionExclusions())
      );
    }
    return new MapBasedInputRow(
        parseSpec.getTimestampSpec().extractTimestamp(record),
        dimensions,
        record
    );
  }

  @Override
  public List<InputRow> parseBatch(ByteBuffer input)
  {
    if (parser == null) {
      // parser should be created when it is really used to avoid unnecessary initialization of the underlying
      // parseSpec.
      parser = parseSpec.makeParser();
      initDescriptor();
    }

    List<InputRow> rows = new ArrayList<InputRow>();
    ImmutableList ret;
    if (this.isLengthDelimited) {
      while (true) {
        int firstByte;
        try {
          firstByte = input.get();
        }
        catch (BufferUnderflowException e) {
          break;
        }

        if (firstByte == -1) {
          throw new ParseException("Malformed length delimited Protobuf error");
        }

        int bodyLength;
        try {
          bodyLength = readRawVarint32(firstByte, input);
        }
        catch (IAE e) {
          throw new ParseException(e, "Malformed length headerLength delimited Protobuf parse error");
        }
        rows.add(parseInput(input, bodyLength));
      }
      ret = ImmutableList.copyOf(rows);
    } else {
      ret = ImmutableList.of(parseInput(input));
    }
    return ret;
  }

  private Descriptor getDescriptor(String descriptorFilePath)
  {
    InputStream fin;

    fin = this.getClass().getClassLoader().getResourceAsStream(descriptorFilePath);
    if (fin == null) {
      URL url;
      try {
        url = new URL(descriptorFilePath);
      }
      catch (MalformedURLException e) {
        throw new ParseException(e, "Descriptor not found in class path or malformed URL:" + descriptorFilePath);
      }
      try {
        fin = url.openConnection().getInputStream();
      }
      catch (IOException e) {
        throw new ParseException(e, "Cannot read descriptor file: " + url);
      }
    }

    DynamicSchema dynamicSchema;
    try {
      dynamicSchema = DynamicSchema.parseFrom(fin);
    }
    catch (Descriptors.DescriptorValidationException e) {
      throw new ParseException(e, "Invalid descriptor file: " + descriptorFilePath);
    }
    catch (IOException e) {
      throw new ParseException(e, "Cannot read descriptor file: " + descriptorFilePath);
    }

    Set<String> messageTypes = dynamicSchema.getMessageTypes();
    if (messageTypes.size() == 0) {
      throw new ParseException("No message types found in the descriptor: " + descriptorFilePath);
    }

    String messageType = protoMessageType == null ? (String) messageTypes.toArray()[0] : protoMessageType;
    Descriptor desc = dynamicSchema.getMessageDescriptor(messageType);
    if (desc == null) {
      throw new ParseException(
          StringUtils.format(
              "Protobuf message type %s not found in the specified descriptor.  Available messages types are %s",
              protoMessageType,
              messageTypes
          )
      );
    }
    return desc;
  }

  private int readRawVarint32(int firstByte, ByteBuffer input) throws IAE
  {
    if ((firstByte & 128) == 0) {
      return firstByte;
    } else {
      int result = firstByte & 127;

      int offset;
      int b;
      for (offset = 7; offset < 32; offset += 7) {
        b = input.get();
        if (b == -1) {
          throw new IAE(StringUtils.format("Invalid VarInt32. -1 was found at offset %d", input.position()));
        }

        result |= (b & 127) << offset;
        if ((b & 128) == 0) {
          return result;
        }
      }

      while (offset < 64) {
        b = input.get();
        if (b == -1) {
          throw new IAE(StringUtils.format("Invalid VarInt32. -1 was found at offset %d", input.position()));
        }

        if ((b & 128) == 0) {
          return result;
        }

        offset += 7;
      }

      throw new IAE("Invalid VarInt32. Too big for int32");
    }
  }
}
