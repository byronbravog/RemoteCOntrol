# Control Presentation Remote

Proyecto para controlar presentaciones desde un teléfono Android a una Mac mediante Wi‑Fi.

Incluye:
- Android app con botones físicos de volumen para avanzar o retroceder diapositivas
- app Swift receptora en macOS, visible en la barra de menús, para convertir comandos en pulsaciones de teclado
- PIN de emparejamiento para autorizar conexiones

## Estructura

- android/  → proyecto Android Studio
- mac/      → receptor para la Mac

## Requisitos

- Android Studio
- Kotlin
- Swift 5.9+ en la Mac (Xcode o Swift Command Line Tools)
- una Mac y un teléfono Android en la misma red Wi‑Fi

## Cómo probar

1. Abre la carpeta android en Android Studio.
2. Sigue el proceso de sincronización del proyecto.
3. Ejecuta la app en el teléfono Android.
4. En la Mac, desde la raíz del proyecto, ejecuta `swift run --package-path mac`.
5. Introduce la IP de la Mac en la app y el PIN que muestre el servidor.
6. Usa VOL+ para siguiente y VOL− para anterior.

## Comandos enviados desde Android

- NEXT
- PREVIOUS
- START
- BLACK
- END

## Teclas simuladas en la Mac

- NEXT → Flecha derecha
- PREVIOUS → Flecha izquierda
- START → F5
- BLACK → B
- END → Esc

La app aparece como un icono de reproducción en la barra de menús y muestra el PIN
actual. Para que las teclas simuladas funcionen, añade la app o el ejecutable a
**Configuración del Sistema → Privacidad y seguridad → Accesibilidad**.
