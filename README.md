## SmartPlugMonitor

SmartPlugMonitor is a simple Android application developed as a hobby project with the help of AI. It monitors the real-time power consumption of a Tuya-compatible smart plug over the local network and detects when a connected appliance or device has finished its cycle.
The app communicates directly with the plug on your local network (LAN) and does not require the Tuya cloud, ensuring better privacy and local responsiveness.

### Requirements

* Android 7.1+
* Compatible Tuya smart plug
* Local IP address and Local Key

### Local Key

A Local Key is required for local communication. You can use tools like TinyTuya to retrieve it from your Tuya account.

### Device Detection Logic

The app determines when a device has finished its operation based on two configurable parameters:

* Power Threshold (W): The wattage limit below which the countdown starts.
* Duration (s): The countdown timer. The cycle is considered finished only if the power stays continuously below the Power Threshold for this amount of time.

### Examples of Configuration

* Washing Machine: Set the Power Threshold according to its idle usage. To avoid false alarms caused by temporary pauses between washing and spinning cycles, set the Duration parameter slightly longer than the machine's longest known pause.
* Makita Double Charger: When the batteries are fully charged, the power drop is sudden and stable. You can set the Power Threshold to 10W and the Duration to a short interval (e.g., 10 seconds), as chargers do not have mid-cycle pauses like washing machines.

### Tested Device

* SURFOU Smart WiFi Plug, 16A (Amazon B0BNJ4XJBP) Other Tuya-compatible plugs may work but are untested.

### Disclaimer

This is an independent, unofficial project and is not affiliated with Tuya or SURFOU. The author is not a professional programmer and this is a hobby project. Please do not expect ongoing development, bug fixes, or feature requests to be addressed.
