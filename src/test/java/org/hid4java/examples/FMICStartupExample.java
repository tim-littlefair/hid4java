/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2014-2020 Gary Rowe
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 */

 /*
  * This file is based on Gary Rowe's Fido2AuthenticationExample.java, with 
  * modifications to replace the FIDO2 authentication operations with in 
  * the original file with operations required to connect to and download preset
  * JSON documents from a Fender Mustang LT series amplifier.
  */

package org.hid4java.examples;

import java.util.concurrent.TimeUnit;

import org.hid4java.*;
import org.hid4java.event.HidServicesEvent;
import org.hid4java.jna.HidApi;

/**
 * Demonstrate the USB HID interface using a Fender Mustang/Rumble LT-series modelling guitar amplifier.
 * Presently tested with the LT40S model only.
 * <br>
 * If you have an applicable Fender LT-series device (e.g. Mustang LT25, LT40S, LT50 or Rumble LT25)
 * you may wish to explore its capabilities using this example. Simply plug it in and run the example to
 * see the initial handshake to select a channel and basic device information.
 * <br>
 * You can see some of hid4java features in use such as:
 * <ul>
 * <ul>
 *   <li>device enumeration and selection</li>
 * <li>automatic data read events (new for 0.8.0)</li>
 * <li>low level HID traffic to System.out to assist early stage debugging</li>
 * <li>manual start to enable attach/detach events</li>
 * </ul>
 * <br>
 * Use the following command to try it out:
 * <br>
 * <code>
 * mvn clean test exec:java -Dexec.classpathScope="test" -Dexec.mainClass="org.hid4java.examples.LT40SStartupExample"
 * </code>
 *
 * @since 0.9.0? 
 */
public class FMICStartupExample extends BaseExample {
  
  public static void main(String[] args) throws HidException {

    FMICStartupExample example = new FMICStartupExample();
    example.executeExample();

  }

  private void executeExample() throws HidException {

    printPlatform();

    // Demonstrate low level traffic logging
    HidApi.logTraffic = false;

    // Configure to use custom specification
    HidServicesSpecification hidServicesSpecification = new HidServicesSpecification();

    // Use manual start
    hidServicesSpecification.setAutoStart(false);

    // Use data received events
    hidServicesSpecification.setAutoDataRead(true);
    hidServicesSpecification.setDataReadInterval(500);

    // Get HID services using custom specification
    HidServices hidServices = HidManager.getHidServices(hidServicesSpecification);

    // Register for service events
    hidServices.addHidServicesListener(this);

    // Manually start HID services
    hidServices.start();

    // Enumerate devices looking for FMIC vendor id and LT series usage page
    HidDevice fmicDevice = null;
    for (HidDevice hidDevice : hidServices.getAttachedHidDevices()) {
      if (hidDevice.getVendorId() != 0x1ed8) {
        continue;
      }
      if (hidDevice.getUsage() == 0x01 && hidDevice.getUsagePage() == 0xffffff00) {
        System.out.println(ANSI_GREEN + "Using LT series device: " + hidDevice.getPath() + ANSI_RESET);
        fmicDevice = hidDevice;
        break;
      }
    }

    if (fmicDevice == null) {
      // Shut down and rely on auto-shutdown hook to clear HidApi resources
      System.out.println(ANSI_YELLOW + "No relevant devices attached." + ANSI_RESET);
    } else {

      // Open the device
      if (fmicDevice.isClosed()) {
        System.out.println(ANSI_YELLOW + "Need to open device." + ANSI_RESET);
        if (!fmicDevice.open()) {
          throw new IllegalStateException("Unable to open device.");
        }
        System.out.println(ANSI_YELLOW + "Device opened." + ANSI_RESET);
      } else {
        System.out.println(ANSI_YELLOW + "No need to open device because it is already open." + ANSI_RESET);
      }

      // Perform a USB ReportDescriptor operation to determine general device capabilities
      // Reports can be up to 4096 bytes for complex devices.
      // Probably won't need this but allocate max capacity anyway.
      byte[] reportDescriptor = new byte[4096];
      if (fmicDevice.getReportDescriptor(reportDescriptor) > 0) {
        System.out.println(ANSI_GREEN + "FMIC device report descriptor: " + fmicDevice.getPath() + ANSI_RESET);
        printAsHex2(reportDescriptor,"<");
      }

      // Initialise the Fender Mustang/Rumble device
      handleInitialise(fmicDevice);
    }

    waitAndShutdown(hidServices);
  }

  private void colonSeparatedHexToByteArray(String colonSeparatedHex, byte[] byteArray) {
    String byteHexArray[] = colonSeparatedHex.split(":");
    assert byteArray.length>=byteHexArray.length;
    for(int i=0; i<byteHexArray.length; ++i) {
       byteArray[i] = (byte) Integer.parseInt(byteHexArray[i],16);
    }
  }

  /**
   * Initialise the FIDO2 device and set a communications channel
   *
   * @param hidDevice The device to use
   * @return True if the device is now initialised for use
   */
  private boolean handleInitialise(HidDevice hidDevice) {
    int bytesWritten;

    byte[] ltInitRequestBytes = new byte[64];
    colonSeparatedHexToByteArray("35:09:08:00:8a:07:04:08:00:10", ltInitRequestBytes);
    // Write message to device with zero byte padding
    System.out.println(ANSI_GREEN + "Sending LT init request..." + ANSI_RESET);
    printAsHex2(ltInitRequestBytes,">");
    bytesWritten = hidDevice.write(ltInitRequestBytes, 64, (byte) 0x00, true);
    if (bytesWritten < 0) {
      System.out.println(ANSI_RED + hidDevice.getLastErrorMessage() + ANSI_RESET);
      return false;
    }

    byte[] ltFirmwareVersionRequestBytes = new byte[64];
    colonSeparatedHexToByteArray("35:07:08:00:b2:06:02:08:01:00:10", ltFirmwareVersionRequestBytes);
    System.out.println(ANSI_GREEN + "Sending firmware version request ..." + ANSI_RESET);
    printAsHex2(ltFirmwareVersionRequestBytes,">");
    bytesWritten = hidDevice.write(ltFirmwareVersionRequestBytes, 64, (byte) 0x00, true);
    if (bytesWritten < 0) {
      System.out.println(ANSI_RED + hidDevice.getLastErrorMessage() + ANSI_RESET);
      return false;
    }

    System.out.println(ANSI_BLUE + "Last error: " + hidDevice.getLastErrorMessage() + ANSI_RESET);
    return true;

  }


  // Override functions specific to this example beyond this point
  @Override
  public void hidDataReceived(HidServicesEvent event) {
    // super.hidDataReceived(event);

    // Analyse the response
    byte[] initialiseResponse = event.getDataReceived();
    printAsHex2(initialiseResponse,"<");
  }

  // BaseExample.printAsHex() prints a buffer in full regardless of whether
  // it is mostly zero-filled.
  // This variant replaces trailing zero bytes with '...' 
  // (if and only if at least one trailing zero byte is present).
  // This allows larger buffers to be used without blowing out
  // log files with empty bytes (e.g. for the report descriptor).
  // This variant is also suitable for use with both sent 
  // and received data.
  public static void printAsHex2(byte[] dataSentOrReceived, String directionChar) {
    System.out.printf("%s [%02x]:", directionChar, dataSentOrReceived.length);
    int trailingZeroByteCount = -1; // -1 signifies 'no non-zero bytes seen yet'
    for (int i=dataSentOrReceived.length-1; i>0; --i) {
      if (dataSentOrReceived[i]!=0) {
        trailingZeroByteCount = dataSentOrReceived.length - i - 1;
        break;
      }
    }
    for (int i=0; i<dataSentOrReceived.length; ++i) {
      System.out.printf(" %02x", dataSentOrReceived[i]);
      if (dataSentOrReceived.length-i==trailingZeroByteCount) {
        System.out.printf(" ...");
        break;
      }
    }
    System.out.println(ANSI_RESET);
  }
}
