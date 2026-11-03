package android.content;
public interface SharedPreferences { interface Editor { Editor putFloat(String k,float v); Editor putInt(String k,int v); Editor putLong(String k,long v); Editor putString(String k,String v); Editor putBoolean(String k,boolean v); void apply(); boolean commit(); }
 Editor edit(); float getFloat(String k,float d); int getInt(String k,int d); long getLong(String k,long d); String getString(String k,String d); boolean getBoolean(String k,boolean d); }
