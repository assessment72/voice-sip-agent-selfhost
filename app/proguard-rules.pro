# MongoDB's synchronous driver references optional JVM-only integrations. The app uses
# the standard Android transport and does not enable these optional providers.
-dontwarn com.amazonaws.**
-dontwarn com.github.luben.zstd.**
-dontwarn com.mongodb.crypt.**
-dontwarn com.mongodb.internal.crypt.**
-dontwarn io.netty.**
-dontwarn javax.naming.**
-dontwarn javax.security.**
-dontwarn jnr.unixsocket.**
-dontwarn org.bson.codecs.kotlin.**
-dontwarn org.bson.codecs.kotlinx.**
-dontwarn org.ietf.jgss.**
-dontwarn org.slf4j.**
-dontwarn org.xerial.snappy.**
-dontwarn software.amazon.awssdk.**
