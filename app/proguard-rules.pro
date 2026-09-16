# 当前 release 未开启混淆（minifyEnabled false），以下规则为后续开启时预留。

-keepattributes Signature,*Annotation*,EnclosingMethod,InnerClass,SourceFile,LineNumberTable

# ViewBinding 生成的类
-keep class com.fei.simplemirror.databinding.** { *; }

-dontwarn androidx.camera.**
-keep class androidx.camera.** { *; }

-dontwarn kotlinx.coroutines.**
-keep class kotlin.coroutines.** { *; }
