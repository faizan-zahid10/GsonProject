package com.google.gson.internal.bind;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.internal.JavaVersion;
import com.google.gson.internal.PreJava9DateFormatProvider;
import com.google.gson.internal.bind.util.ISO8601Utils;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TimeZone;

/**
 * This type adapter supports subclasses of date by defining a {@link
 * DefaultDateTypeAdapter.DateType} and then using its {@code createAdapterFactory} methods.
 *
 * <p><b>Important:</b> Instances of this class (or rather the {@link SimpleDateFormat} they use)
 * capture the current default {@link Locale} and {@link TimeZone} when they are created. Therefore
 * avoid storing factories obtained from {@link DateType} in {@code static} fields, since they only
 * create a single adapter instance and its behavior would then depend on when Gson classes are
 * loaded first, and which default {@code Locale} and {@code TimeZone} was used at that point.
 *
 * @author Inderjeet Singh
 * @author Joel Leitch
 */
public final class DefaultDateTypeAdapter<T extends Date> extends TypeAdapter<T> {
  private static final String SIMPLE_NAME = "DefaultDateTypeAdapter";

  /** Factory for {@link Date} adapters which use {@link DateFormat#DEFAULT} as style. */
  public static final TypeAdapterFactory DEFAULT_STYLE_FACTORY =
      new TypeAdapterFactory() {
        @SuppressWarnings("unchecked") // we use a runtime check to make sure the 'T's equal
        @Override
        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> typeToken) {
          return typeToken.getRawType() == Date.class
              ? (TypeAdapter<T>)
                  new DefaultDateTypeAdapter<>(
                      DateType.DATE, DateFormat.DEFAULT, DateFormat.DEFAULT)
              : null;
        }

        @Override
        public String toString() {
          return "DefaultDateTypeAdapter#DEFAULT_STYLE_FACTORY";
        }
      };

  public abstract static class DateType<T extends Date> {
    public static final DateType<Date> DATE =
        new DateType<Date>(Date.class) {
          @Override
          protected Date deserialize(Date date) {
            return date;
          }
        };

    private final Class<T> dateClass;

    protected DateType(Class<T> dateClass) {
      this.dateClass = dateClass;
    }

    protected abstract T deserialize(Date date);

    private TypeAdapterFactory createFactory(DefaultDateTypeAdapter<T> adapter) {
      return TypeAdapters.newFactory(dateClass, adapter);
    }

    public final TypeAdapterFactory createAdapterFactory(String datePattern) {
      return createFactory(new DefaultDateTypeAdapter<>(this, datePattern));
    }

    public final TypeAdapterFactory createAdapterFactory(int dateStyle, int timeStyle) {
      return createFactory(new DefaultDateTypeAdapter<>(this, dateStyle, timeStyle));
    }
  }

  private final DateType<T> dateType;
  private final ThreadLocal<List<DateFormat>> dateFormatsThreadLocal =
      ThreadLocal.withInitial(() -> new ArrayList<>());

  private DefaultDateTypeAdapter(DateType<T> dateType, String datePattern) {
    this.dateType = Objects.requireNonNull(dateType);
    initializeDateFormats(datePattern);
  }

  private DefaultDateTypeAdapter(DateType<T> dateType, int dateStyle, int timeStyle) {
    this.dateType = Objects.requireNonNull(dateType);
    initializeDateFormats(dateStyle, timeStyle);
  }

  private void initializeDateFormats(String datePattern) {
    List<DateFormat> dateFormats = dateFormatsThreadLocal.get();
    dateFormats.clear();
    dateFormats.add(new SimpleDateFormat(datePattern, Locale.US));
    addLocaleSpecificDateFormat(datePattern);
  }

  private void initializeDateFormats(int dateStyle, int timeStyle) {
    List<DateFormat> dateFormats = dateFormatsThreadLocal.get();
    dateFormats.clear();
    dateFormats.add(DateFormat.getDateTimeInstance(dateStyle, timeStyle, Locale.US));
    addLocaleSpecificDateFormat(dateStyle, timeStyle);
    if (JavaVersion.isJava9OrLater()) {
      dateFormats.add(PreJava9DateFormatProvider.getUsDateTimeFormat(dateStyle, timeStyle));
    }
  }

  private void addLocaleSpecificDateFormat(String datePattern) {
    if (!Locale.getDefault().equals(Locale.US)) {
      dateFormatsThreadLocal.get().add(new SimpleDateFormat(datePattern));
    }
  }

  private void addLocaleSpecificDateFormat(int dateStyle, int timeStyle) {
    if (!Locale.getDefault().equals(Locale.US)) {
      dateFormatsThreadLocal.get().add(DateFormat.getDateTimeInstance(dateStyle, timeStyle));
    }
  }

  @Override
  public void write(JsonWriter out, Date value) throws IOException {
    if (value == null) {
      out.nullValue();
      return;
    }

    DateFormat dateFormat = dateFormatsThreadLocal.get().get(0);
    synchronized (dateFormatsThreadLocal) {
      String dateFormatAsString = dateFormat.format(value);
      out.value(dateFormatAsString);
    }
  }

  @Override
  public T read(JsonReader in) throws IOException {
    if (in.peek() == JsonToken.NULL) {
      in.nextNull();
      return null;
    }
    Date date = deserializeToDate(in);
    return dateType.deserialize(date);
  }

  private Date deserializeToDate(JsonReader in) throws IOException {
    String dateStr = in.nextString();
    List<DateFormat> dateFormats = dateFormatsThreadLocal.get();

    synchronized (dateFormatsThreadLocal) {
      for (DateFormat dateFormat : dateFormats) {
        TimeZone originalTimeZone = dateFormat.getTimeZone();
        try {
          return dateFormat.parse(dateStr);
        } catch (ParseException ignored) {
          // Continue with the next format
        } finally {
          dateFormat.setTimeZone(originalTimeZone);
        }
      }
    }

    return parseISO8601Date(dateStr, in);
  }

  private Date parseISO8601Date(String dateStr, JsonReader in) throws IOException {
    try {
      return ISO8601Utils.parse(dateStr, new ParsePosition(0));
    } catch (ParseException e) {
      throw new JsonSyntaxException(
          "Failed parsing '" + dateStr + "' as Date; at path " + in.getPreviousPath(), e);
    }
  }

  @Override
  public String toString() {
    DateFormat defaultFormat = dateFormatsThreadLocal.get().get(0);
    return defaultFormat instanceof SimpleDateFormat
        ? SIMPLE_NAME + '(' + ((SimpleDateFormat) defaultFormat).toPattern() + ')'
        : SIMPLE_NAME + '(' + defaultFormat.getClass().getSimpleName() + ')';
  }
}
