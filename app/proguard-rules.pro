# Код не використовує рефлексію; правила Compose/AndroidX ідуть разом з бібліотеками.
-dontwarn org.json.**
# Paho завантажує логер і ресурси за іменем класу
-keep class org.eclipse.paho.client.mqttv3.** { *; }
-dontwarn org.eclipse.paho.**
