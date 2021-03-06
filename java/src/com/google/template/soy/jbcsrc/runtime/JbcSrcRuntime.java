/*
 * Copyright 2015 Google Inc.
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

package com.google.template.soy.jbcsrc.runtime;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.template.soy.data.AbstractLoggingAdvisingAppendable;
import com.google.template.soy.data.ForwardingLoggingAdvisingAppendable;
import com.google.template.soy.data.LogStatement;
import com.google.template.soy.data.LoggingAdvisingAppendable;
import com.google.template.soy.data.LoggingFunctionInvocation;
import com.google.template.soy.data.SanitizedContent;
import com.google.template.soy.data.SanitizedContent.ContentKind;
import com.google.template.soy.data.SoyLegacyObjectMap;
import com.google.template.soy.data.SoyMap;
import com.google.template.soy.data.SoyRecord;
import com.google.template.soy.data.SoyValue;
import com.google.template.soy.data.SoyValueProvider;
import com.google.template.soy.data.UnsafeSanitizedContentOrdainer;
import com.google.template.soy.data.restricted.IntegerData;
import com.google.template.soy.data.restricted.NullData;
import com.google.template.soy.data.restricted.NumberData;
import com.google.template.soy.data.restricted.SoyString;
import com.google.template.soy.data.restricted.StringData;
import com.google.template.soy.data.restricted.UndefinedData;
import com.google.template.soy.jbcsrc.api.RenderResult;
import com.google.template.soy.jbcsrc.shared.CompiledTemplate;
import com.google.template.soy.jbcsrc.shared.RenderContext;
import com.google.template.soy.msgs.restricted.SoyMsgPart;
import com.google.template.soy.msgs.restricted.SoyMsgPlaceholderPart;
import com.google.template.soy.msgs.restricted.SoyMsgPluralPart;
import com.google.template.soy.msgs.restricted.SoyMsgPluralRemainderPart;
import com.google.template.soy.msgs.restricted.SoyMsgRawTextPart;
import com.google.template.soy.msgs.restricted.SoyMsgSelectPart;
import com.google.template.soy.shared.internal.ShortCircuitable;
import com.google.template.soy.shared.restricted.SoyJavaFunction;
import com.google.template.soy.shared.restricted.SoyJavaPrintDirective;
import com.ibm.icu.util.ULocale;
import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Runtime utilities uniquely for the {@code jbcsrc} backend.
 *
 * <p>This class is public so it can be be used by generated template code. Please do not use it
 * from client code.
 */
@SuppressWarnings("ShortCircuitBoolean")
public final class JbcSrcRuntime {
  public static final SoyValueProvider NULL_PROVIDER =
      new SoyValueProvider() {
        @Override
        public RenderResult status() {
          return RenderResult.done();
        }

        @Override
        public SoyValue resolve() {
          return null;
        }

        @Override
        public RenderResult renderAndResolve(LoggingAdvisingAppendable appendable, boolean isLast)
            throws IOException {
          appendable.append("null");
          return RenderResult.done();
        }

        @Override
        public String toString() {
          return "NULL_PROVIDER";
        }
      };

  public static AssertionError unexpectedStateError(int state) {
    return new AssertionError("Unexpected state requested: " + state);
  }

  public static boolean stringEqualsAsNumber(String expr, double number) {
    if (expr == null) {
      return false;
    }

    try {
      return Double.parseDouble(expr) == number;
    } catch (NumberFormatException nfe) {
      return false;
    }
  }

  /** Helper function to translate NullData -> null when resolving a SoyValueProvider. */
  public static SoyValue resolveSoyValueProvider(SoyValueProvider provider) {
    SoyValue value = provider.resolve();
    return handleTofuNull(value);
  }

  private static SoyValue handleTofuNull(SoyValue value) {
    if (value instanceof NullData | value instanceof UndefinedData) {
      return null;
    }
    return value;
  }

  /**
   * Helper function to make SoyRecord.getFieldProvider a non-nullable function by returning {@link
   * #NULL_PROVIDER} for missing fields.
   */
  public static SoyValueProvider getFieldProvider(SoyRecord record, String field) {
    if (record == null) {
      throw new NullPointerException("Attempted to access field '" + field + "' of null");
    }
    // TODO(lukes): ideally this would be the behavior of getFieldProvider, but Tofu relies on it
    // returning null to interpret it as 'undefined'. http://b/20537225 describes the issues in Tofu
    SoyValueProvider provider = record.getFieldProvider(field);
    // | instead of || avoids a branch
    return (provider == null | provider instanceof NullData) ? NULL_PROVIDER : provider;
  }

  /**
   * Casts the given type to SoyString or throws a ClassCastException.
   *
   * <p>TODO(b/74259210): Change this to conditionally log a warning
   */
  public static SoyString checkSoyString(Object o) {
    // TODO(tomnguyen) Log a future error here if o is SanitizedCompatString
    return (SoyString) o;
  }

  /**
   * Helper function to translate null -> NullData when calling SoyJavaFunctions that may expect it.
   *
   * <p>In the long run we should either fix ToFu (and all SoyJavaFunctions) to not use NullData or
   * we should introduce custom SoyFunction implementations for have come from SoyValueProvider.
   */
  public static SoyValue callSoyFunction(SoyJavaFunction function, List<SoyValue> args) {
    for (int i = 0; i < args.size(); i++) {
      if (args.get(i) == null) {
        args.set(i, NullData.INSTANCE);
      }
    }
    return handleTofuNull(function.computeForJava(args));
  }

  /**
   * Helper function to translate null -> NullData when calling SoyJavaPrintDirectives that may
   * expect it.
   */
  public static SoyValue applyPrintDirective(
      SoyJavaPrintDirective directive, SoyValue value, List<SoyValue> args) {
    value = value == null ? NullData.INSTANCE : value;
    for (int i = 0; i < args.size(); i++) {
      if (args.get(i) == null) {
        args.set(i, NullData.INSTANCE);
      }
    }
    return directive.applyForJava(value, args);
  }

  /**
   * Wraps a given template with a collection of escapers to apply.
   *
   * @param delegate The delegate template to render
   * @param directives The set of directives to apply
   */
  public static CompiledTemplate applyEscapers(
      CompiledTemplate delegate, ImmutableList<SoyJavaPrintDirective> directives) {
    ContentKind kind = delegate.kind();
    if (canSkipEscaping(directives, kind)) {
      return delegate;
    }
    return new EscapedCompiledTemplate(delegate, directives, kind);
  }

  /**
   * Identifies some cases where the combination of directives and content kind mean we can skip
   * applying the escapers. This is an opportunistic optimization, it is possible that we will fail
   * to skip escaping in some cases where we could and that is OK. However, there should never be a
   * case where we skip escaping and but the escapers would actually modify the input.
   */
  private static boolean canSkipEscaping(
      ImmutableList<SoyJavaPrintDirective> directives, @Nullable ContentKind kind) {
    if (kind == null) {
      return false;
    }
    for (SoyJavaPrintDirective directive : directives) {
      if (!(directive instanceof ShortCircuitable)
          || !((ShortCircuitable) directive).isNoopForKind(kind)) {
        return false;
      }
    }
    return true;
  }

  public static SoyValueProvider getSoyListItem(List<SoyValueProvider> list, long index) {
    if (list == null) {
      throw new NullPointerException("Attempted to access list item '" + index + "' of null");
    }
    int size = list.size();
    // use & instead of && to avoid a branch
    if (index < size & index >= 0) {
      SoyValueProvider soyValueProvider = list.get((int) index);
      return soyValueProvider == null ? NULL_PROVIDER : soyValueProvider;
    }
    return NULL_PROVIDER;
  }

  public static RenderResult getListStatus(List<? extends SoyValueProvider> soyValueProviders) {
    // avoid allocating an iterator
    int size = soyValueProviders.size();
    for (int i = 0; i < size; i++) {
      RenderResult result = soyValueProviders.get(i).status();
      if (!result.isDone()) {
        return result;
      }
    }
    return RenderResult.done();
  }

  public static RenderResult getMapStatus(
      Map<String, ? extends SoyValueProvider> soyValueProviders) {
    for (SoyValueProvider value : soyValueProviders.values()) {
      RenderResult result = value.status();
      if (!result.isDone()) {
        return result;
      }
    }
    return RenderResult.done();
  }

  public static SoyValueProvider getSoyMapItem(SoyMap soyMap, SoyValue key) {
    if (soyMap == null) {
      throw new NullPointerException("Attempted to access map item '" + key + "' of null");
    }
    if (key == null) {
      key = NullData.INSTANCE;
    }
    SoyValueProvider soyValueProvider = soyMap.getProvider(key);
    return soyValueProvider == null ? NULL_PROVIDER : soyValueProvider;
  }

  public static SoyValueProvider getSoyLegacyObjectMapItem(
      SoyLegacyObjectMap legacyObjectMap, SoyValue key) {
    if (legacyObjectMap == null) {
      throw new NullPointerException("Attempted to access map item '" + key + "' of null");
    }
    SoyValueProvider soyValueProvider = legacyObjectMap.getItemProvider(key);
    return soyValueProvider == null ? NULL_PROVIDER : soyValueProvider;
  }

  /** Render a 'complex' message containing with placeholders. */
  public static void renderSoyMsgPartsWithPlaceholders(
      ImmutableList<SoyMsgPart> msgParts,
      @Nullable ULocale locale,
      Map<String, Object> placeholders,
      Appendable out)
      throws IOException {
    // TODO(lukes): the initial plural/select nesting structure of the SoyMsg is determined at
    // compile time (though the number of cases varies per locale).
    // We could allow the generated code to call renderSelect/renderPlural directly as a
    // microoptimization.  This could potentially allow us to eliminate the hashmap entry+boxing for
    // plural variables when rendering a direct plural tag.  ditto for selects, though that is
    // more complicated due to the fact that selects can be nested.
    SoyMsgPart firstPart = msgParts.get(0);
    if (firstPart instanceof SoyMsgPluralPart) {
      renderPlural(locale, (SoyMsgPluralPart) firstPart, placeholders, out);
    } else if (firstPart instanceof SoyMsgSelectPart) {
      renderSelect(locale, (SoyMsgSelectPart) firstPart, placeholders, out);
    } else {
      // avoid allocating the iterator
      for (int i = 0; i < msgParts.size(); i++) {
        SoyMsgPart msgPart = msgParts.get(i);
        if (msgPart instanceof SoyMsgRawTextPart) {
          writeRawText((SoyMsgRawTextPart) msgPart, out);
        } else if (msgPart instanceof SoyMsgPlaceholderPart) {
          writePlaceholder((SoyMsgPlaceholderPart) msgPart, placeholders, out);
        } else {
          throw new AssertionError("unexpected part: " + msgPart);
        }
      }
    }
  }

  /**
   * Render a {@code {select}} part of a message. Most of the complexity is handled by {@link
   * SoyMsgSelectPart#lookupCase} all this needs to do is apply the placeholders to all the
   * children.
   */
  private static void renderSelect(
      @Nullable ULocale locale,
      SoyMsgSelectPart firstPart,
      Map<String, Object> placeholders,
      Appendable out)
      throws IOException {
    String selectCase = getSelectCase(placeholders, firstPart.getSelectVarName());
    for (SoyMsgPart casePart : firstPart.lookupCase(selectCase)) {
      if (casePart instanceof SoyMsgSelectPart) {
        renderSelect(locale, (SoyMsgSelectPart) casePart, placeholders, out);
      } else if (casePart instanceof SoyMsgPluralPart) {
        renderPlural(locale, (SoyMsgPluralPart) casePart, placeholders, out);
      } else if (casePart instanceof SoyMsgPlaceholderPart) {
        writePlaceholder((SoyMsgPlaceholderPart) casePart, placeholders, out);
      } else if (casePart instanceof SoyMsgRawTextPart) {
        writeRawText((SoyMsgRawTextPart) casePart, out);
      } else {
        // select cannot directly contain remainder nodes.
        throw new AssertionError("unexpected part: " + casePart);
      }
    }
  }

  /**
   * Render a {@code {plural}} part of a message. Most of the complexity is handled by {@link
   * SoyMsgPluralPart#lookupCase} all this needs to do is apply the placeholders to all the
   * children.
   */
  private static void renderPlural(
      @Nullable ULocale locale,
      SoyMsgPluralPart plural,
      Map<String, Object> placeholders,
      Appendable out)
      throws IOException {
    long pluralValue = getPlural(placeholders, plural.getPluralVarName());
    for (SoyMsgPart casePart : plural.lookupCase(pluralValue, locale)) {
      if (casePart instanceof SoyMsgPlaceholderPart) {
        writePlaceholder((SoyMsgPlaceholderPart) casePart, placeholders, out);

      } else if (casePart instanceof SoyMsgRawTextPart) {
        writeRawText((SoyMsgRawTextPart) casePart, out);

      } else if (casePart instanceof SoyMsgPluralRemainderPart) {
        out.append(String.valueOf(pluralValue - plural.getOffset()));

      } else {
        // Plural parts will not have nested plural/select parts.  So, this is an error.
        throw new AssertionError("unexpected part: " + casePart);
      }
    }
  }

  /** Returns the select case variable value. */
  private static String getSelectCase(Map<String, Object> placeholders, String selectVarName) {
    String selectCase = (String) placeholders.get(selectVarName);
    if (selectCase == null) {
      throw new IllegalArgumentException("No value provided for select: '" + selectVarName + "'");
    }
    return selectCase;
  }

  /** Returns the plural case variable value. */
  private static long getPlural(Map<String, Object> placeholders, String pluralVarName) {
    IntegerData pluralValue = (IntegerData) placeholders.get(pluralVarName);
    if (pluralValue == null) {
      throw new IllegalArgumentException("No value provided for plural: '" + pluralVarName + "'");
    }
    return pluralValue.longValue();
  }

  /** Append the placeholder to the output stream. */
  private static void writePlaceholder(
      SoyMsgPlaceholderPart placeholder, Map<String, Object> placeholders, Appendable out)
      throws IOException {
    String placeholderName = placeholder.getPlaceholderName();
    String str = (String) placeholders.get(placeholderName);
    if (str == null) {
      throw new IllegalArgumentException(
          "No value provided for placeholder: '" + placeholderName + "'");
    }
    out.append(str);
  }

  /** Append the raw text segment to the output stream. */
  private static void writeRawText(SoyMsgRawTextPart msgPart, Appendable out) throws IOException {
    out.append(msgPart.getRawText());
  }

  private static final LoggingAdvisingAppendable LOGGER =
      new AbstractLoggingAdvisingAppendable() {
        @Override
        public final boolean softLimitReached() {
          return false;
        }

        @Override
        protected final void doAppend(char c) throws IOException {
          System.out.append(c);
        }

        @Override
        protected final void doAppend(CharSequence csq, int start, int end) throws IOException {
          System.out.append(csq, start, end);
        }

        @Override
        protected final void doAppend(CharSequence csq) throws IOException {
          System.out.append(csq);
        }

        @Override
        protected final void doEnterLoggableElement(LogStatement statement) {}

        @Override
        protected final void doExitLoggableElement() {}

        @Override
        protected void doAppendLoggingFunctionInvocation(
            LoggingFunctionInvocation funCall, ImmutableList<Function<String, String>> escapers)
            throws IOException {
          String val = funCall.placeholderValue();
          for (Function<String, String> directive : escapers) {
            val = directive.apply(val);
          }
          System.out.append(val);
        }
      };

  /** Determines if the operand's string form can be equality-compared with a string. */
  public static boolean compareNullableString(@Nullable String string, SoyValue other) {
    // This is a parallel version of SharedRuntime.compareString except it can handle a null LHS.

    // This follows similarly to the Javascript specification, to ensure similar operation
    // over Javascript and Java: http://www.ecma-international.org/ecma-262/5.1/#sec-11.9.3
    if (other instanceof StringData || other instanceof SanitizedContent) {
      return Objects.equals(string, other.toString());
    }
    if (other instanceof NumberData) {
      if (string == null) {
        return false;
      }

      try {
        // Parse the string as a number.
        return Double.parseDouble(string) == other.numberValue();
      } catch (NumberFormatException nfe) {
        // Didn't parse as a number.
        return false;
      }
    }
    return false;
  }

  public static LoggingAdvisingAppendable logger() {
    return LOGGER;
  }

  public static int rangeLoopLength(int start, int end, int step) {
    int length = end - start;
    if ((length ^ step) < 0) {
      return 0;
    }
    return length / step + (length % step == 0 ? 0 : 1);
  }

  public static boolean coerceToBoolean(double v) {
    // NaN and 0 should both be falsy, all other numbers are truthy
    // use & instead of && to avoid a branch
    return v != 0.0 & !Double.isNaN(v);
  }

  public static String coerceToString(@Nullable SoyValue v) {
    return v == null ? "null" : v.coerceToString();
  }

  /** Wraps a compiled template to apply escaping directives. */
  private static final class EscapedCompiledTemplate implements CompiledTemplate {
    private final CompiledTemplate delegate;
    private final ImmutableList<SoyJavaPrintDirective> directives;
    @Nullable private final ContentKind kind;

    // Note: render() may be called multiple times as part of a render operation that detaches
    // halfway through.  So we need to store the buffer in a field, but we never need to reset it.
    private final LoggingAdvisingAppendable buffer = LoggingAdvisingAppendable.buffering();

    EscapedCompiledTemplate(
        CompiledTemplate delegate,
        List<SoyJavaPrintDirective> directives,
        @Nullable ContentKind kind) {
      this.delegate = delegate;
      this.directives = ImmutableList.copyOf(directives);
      this.kind = kind;
    }

    @Override
    public RenderResult render(LoggingAdvisingAppendable appendable, RenderContext context)
        throws IOException {
      RenderResult result = delegate.render(buffer, context);
      if (result.isDone()) {
        SoyValue resultData =
            kind == null
                ? StringData.forValue(buffer.toString())
                : UnsafeSanitizedContentOrdainer.ordainAsSafe(buffer.toString(), kind);
        for (SoyJavaPrintDirective directive : directives) {
          resultData = directive.applyForJava(resultData, ImmutableList.<SoyValue>of());
        }
        appendable.append(resultData.coerceToString());
      }
      return result;
    }

    @Override
    @Nullable
    public ContentKind kind() {
      return kind;
    }
  }

  /**
   * Returns a {@link LoggingAdvisingAppendable} that:
   *
   * <ul>
   *   <li>Forwards all {@link LoggingAdvisingAppendable} methods to {@code delegate}
   *   <li>Implements {@link Closeable} and forwards all {@link Closeable#close} calls to the given
   *       closeables in order.
   * </ul>
   *
   * <p>This strategy allows us to make certain directives closeable without requiring them all to
   * be since this wrapper can propagate the close signals in the rare case that a closeable
   * directive is wrapped with a non closeable one (or multiple closeable wrappers are composed)
   */
  public static ClosePropagatingAppendable propagateClose(
      LoggingAdvisingAppendable delegate, ImmutableList<Closeable> closeables) {
    return new ClosePropagatingAppendable(delegate, closeables);
  }

  private static final class ClosePropagatingAppendable extends ForwardingLoggingAdvisingAppendable
      implements Closeable {
    final ImmutableList<Closeable> closeables;

    ClosePropagatingAppendable(
        LoggingAdvisingAppendable delegate, ImmutableList<Closeable> closeables) {
      super(delegate);
      this.closeables = closeables;
    }

    @Override
    public void close() throws IOException {
      // This looks buggy since we don't catch IOExceptions and then propagate close to the
      // remaining closeables, but that is fine given our usecase since all these closeables are
      // really wrapping each other and the close command is only to flush a buffer, so if any
      // throw we just abort anyway and if any of them throw it doesn't really matter if some data
      // is stuck in a buffer since the whole render is going to fail.
      for (Closeable c : closeables) {
        c.close();
      }
    }
  }
}
