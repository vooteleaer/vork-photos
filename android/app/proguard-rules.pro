# The JNI library calls VorkStorage.connect() by name.
-keep class org.vork.photos.VorkStorage { public static int connect(); }
