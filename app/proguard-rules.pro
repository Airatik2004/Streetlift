# kotlinx.serialization — модели бэкапа
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class ru.streetlift.tracker.**$$serializer { *; }
-keepclassmembers class ru.streetlift.tracker.** {
    *** Companion;
}
-keepclasseswithmembers class ru.streetlift.tracker.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Room-сущности и enum'ы, которые попадают в JSON
-keep class ru.streetlift.tracker.data.db.** { *; }
-keep enum ru.streetlift.tracker.domain.** { *; }
