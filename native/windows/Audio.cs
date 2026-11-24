using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;

namespace LanLink {
  public static class RemoteAudio {
    [StructLayout(LayoutKind.Sequential, Pack = 2)]
    struct WaveFormat {
      public ushort Tag, Channels;
      public uint Rate, BytesPerSecond;
      public ushort Alignment, Bits, Extra;
    }

    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    class EndpointEnumerator { }

    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IEndpoints {
      void EnumEndpoints(int flow, uint mask, out IntPtr devices);
      void GetDefaultAudioEndpoint(int flow, int role, out IEndpoint endpoint);
    }

    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IEndpoint {
      void Activate(ref Guid iid, uint context, IntPtr parameters, [MarshalAs(UnmanagedType.IUnknown)] out object instance);
    }

    [ComImport, Guid("1CB9AD4C-DBFA-4C32-B178-C2F568A703B2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioClient {
      void Initialize(int mode, uint flags, long duration, long periodicity, ref WaveFormat format, IntPtr session);
      void GetBufferSize(out uint frames);
      void GetStreamLatency(out long latency);
      void GetCurrentPadding(out uint frames);
      void IsFormatSupported(int mode, ref WaveFormat format, out IntPtr closest);
      void GetMixFormat(out IntPtr format);
      void GetDevicePeriod(out long defaultPeriod, out long minimum);
      void Start();
      void Stop();
      void Reset();
      void SetEventHandle(IntPtr handle);
      void GetService(ref Guid iid, [MarshalAs(UnmanagedType.IUnknown)] out object instance);
    }

    [ComImport, Guid("C8ADBD64-E71E-48A0-A4DE-185C395CD317"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface ICapture {
      void GetBuffer(out IntPtr data, out uint frames, out uint flags, out ulong position, out ulong timestamp);
      void ReleaseBuffer(uint frames);
      void GetNextPacketSize(out uint frames);
    }

    [ComImport, Guid("F294ACFC-3146-4483-A7BF-ADDCA7C260E2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IRender {
      void GetBuffer(uint frames, out IntPtr data);
      void ReleaseBuffer(uint frames, uint flags);
    }

    public static void Run(bool capture) {
      var enumerator = (IEndpoints)new EndpointEnumerator();
      IEndpoint endpoint;
      enumerator.GetDefaultAudioEndpoint(0, 0, out endpoint);
      object instance;
      var iid = typeof(IAudioClient).GUID;
      endpoint.Activate(ref iid, 23, IntPtr.Zero, out instance);
      var client = (IAudioClient)instance;
      var format = new WaveFormat {Tag=1,Channels=2,Rate=48000,BytesPerSecond=192000,Alignment=4,Bits=16};
      const uint convert = 0x88000000;
      client.Initialize(0, convert | (capture ? 0x20000U : 0U), 400000, 0, ref format, IntPtr.Zero);
      if (capture) Capture(client); else Play(client);
    }

    static void Capture(IAudioClient client) {
      object instance;
      var iid = typeof(ICapture).GUID;
      client.GetService(ref iid, out instance);
      var capture = (ICapture)instance;
      var output = Console.OpenStandardOutput();
      var buffer = new byte[3840];
      client.Start();
      for (;;) {
        uint size;
        capture.GetNextPacketSize(out size);
        if (size == 0) { Thread.Sleep(4); continue; }
        IntPtr pointer;
        uint frames, flags;
        ulong position, timestamp;
        capture.GetBuffer(out pointer, out frames, out flags, out position, out timestamp);
        try {
          int count = checked((int)frames * 4);
          if (count > buffer.Length) buffer = new byte[count];
          if ((flags & 2) == 0) Marshal.Copy(pointer, buffer, 0, count);
          else Array.Clear(buffer, 0, count);
          output.Write(buffer, 0, count);
        } finally { capture.ReleaseBuffer(frames); }
      }
    }

    static void Play(IAudioClient client) {
      object instance;
      var iid = typeof(IRender).GUID;
      client.GetService(ref iid, out instance);
      var render = (IRender)instance;
      uint capacity;
      client.GetBufferSize(out capacity);
      var input = Console.OpenStandardInput();
      var buffer = new byte[3840];
      bool started = false;
      for (;;) {
        int offset = 0;
        while (offset < buffer.Length) {
          int read = input.Read(buffer, offset, buffer.Length - offset);
          if (read == 0) { if (started) client.Stop(); return; }
          offset += read;
        }
        uint left = 960;
        offset = 0;
        while (left > 0) {
          uint occupied;
          client.GetCurrentPadding(out occupied);
          uint frames = Math.Min(left, capacity - occupied);
          if (frames == 0) { Thread.Sleep(3); continue; }
          IntPtr pointer;
          render.GetBuffer(frames, out pointer);
          int bytes = (int)frames * 4;
          Marshal.Copy(buffer, offset, pointer, bytes);
          render.ReleaseBuffer(frames, 0);
          offset += bytes;
          left -= frames;
          if (!started) { client.Start(); started = true; }
        }
      }
    }
  }
}
