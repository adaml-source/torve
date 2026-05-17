package is.xyz.mpv;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.Surface;
import androidx.annotation.NonNull;
import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("unused")
public final class MPVLib {

  private static final List<EventObserver> observers = new ArrayList<>();
  private static final List<LogObserver> logObservers = new ArrayList<>();

  private MPVLib() {}

  public static native void create(Context context);
  public static native void init();
  public static native void destroy();

  public static native void attachSurface(Surface surface);
  public static native void detachSurface();

  public static native void command(@NonNull String[] cmd);
  public static native int setOptionString(String name, String value);
  public static native Bitmap grabThumbnail(int dimension);

  public static native void setPropertyBoolean(@NonNull String name, @NonNull Boolean value);
  public static native void setPropertyInt(@NonNull String name, @NonNull Integer value);
  public static native void setPropertyDouble(@NonNull String name, @NonNull Double value);
  public static native void setPropertyString(@NonNull String name, @NonNull String value);

  public static native Boolean getPropertyBoolean(@NonNull String name);
  public static native Integer getPropertyInt(@NonNull String name);
  public static native Double getPropertyDouble(@NonNull String name);
  public static native String getPropertyString(@NonNull String name);

  public static native void observeProperty(@NonNull String name, int format);

  public static void addObserver(EventObserver observer) {
    synchronized (observers) {
      observers.add(observer);
    }
  }

  public static void removeObserver(EventObserver observer) {
    synchronized (observers) {
      observers.remove(observer);
    }
  }

  public static void addLogObserver(LogObserver observer) {
    synchronized (logObservers) {
      logObservers.add(observer);
    }
  }

  public static void removeLogObserver(LogObserver observer) {
    synchronized (logObservers) {
      logObservers.remove(observer);
    }
  }

  public static void eventProperty(String property, long value) {
    synchronized (observers) {
      for (EventObserver observer : observers) {
        observer.eventProperty(property, value);
      }
    }
    com.torve.android.player.MPVLib.dispatchEventProperty(property, value);
  }

  public static void eventProperty(String property, boolean value) {
    synchronized (observers) {
      for (EventObserver observer : observers) {
        observer.eventProperty(property, value);
      }
    }
    com.torve.android.player.MPVLib.dispatchEventProperty(property, value);
  }

  public static void eventProperty(String property, double value) {
    synchronized (observers) {
      for (EventObserver observer : observers) {
        observer.eventProperty(property, value);
      }
    }
    com.torve.android.player.MPVLib.dispatchEventProperty(property, value);
  }

  public static void eventProperty(String property, String value) {
    synchronized (observers) {
      for (EventObserver observer : observers) {
        observer.eventProperty(property, value);
      }
    }
    com.torve.android.player.MPVLib.dispatchEventProperty(property, value);
  }

  public static void eventProperty(String property) {
    synchronized (observers) {
      for (EventObserver observer : observers) {
        observer.eventProperty(property);
      }
    }
    com.torve.android.player.MPVLib.dispatchEventProperty(property, null);
  }

  public static void event(int eventId) {
    synchronized (observers) {
      for (EventObserver observer : observers) {
        observer.event(eventId);
      }
    }
    com.torve.android.player.MPVLib.dispatchEvent(eventId);
  }

  public static void logMessage(String prefix, int level, String text) {
    synchronized (logObservers) {
      for (LogObserver observer : logObservers) {
        observer.logMessage(prefix, level, text);
      }
    }
    if (com.torve.android.BuildConfig.DEBUG) {
      android.util.Log.d(
          "UpstreamMPVLib",
          prefix + "[" + level + "]: " +
              com.torve.domain.diagnostics.DiagnosticsRedactor.INSTANCE.redact(text));
    }
  }

  public interface EventObserver {
    void eventProperty(@NonNull String property);
    void eventProperty(@NonNull String property, long value);
    void eventProperty(@NonNull String property, boolean value);
    void eventProperty(@NonNull String property, @NonNull String value);
    void eventProperty(@NonNull String property, double value);
    void event(int eventId);
  }

  public interface LogObserver {
    void logMessage(@NonNull String prefix, int level, @NonNull String text);
  }
}
