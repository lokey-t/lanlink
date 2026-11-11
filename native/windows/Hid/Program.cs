using System.Text.Json;
using System.Security.Cryptography;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Security.Cryptography;

if (args.Length != 0)
{
    try
    {
        object result = args switch
        {
            ["--find", var service] => await HidConnection.Find(Guid.ParseExact(service, "D")),
            ["--pair", var address, var type] => await HidConnection.Pair(Convert.ToUInt64(address, 16), Enum.Parse<BluetoothAddressType>(type)),
            _ => throw new ArgumentException(),
        };
        Console.WriteLine(JsonSerializer.Serialize(result));
    }
    catch { Environment.ExitCode = 1; }
    return;
}

var hid = new BluetoothHid();
while (await Console.In.ReadLineAsync() is { } line)
{
    string? id = null;
    try
    {
        using var document = JsonDocument.Parse(line);
        var message = document.RootElement;
        id = message.GetProperty("id").GetString();
        var payload = message.GetProperty("args");
        object result = message.GetProperty("method").GetString() switch
        {
            "hid-start" => await hid.Start(payload.GetProperty("reportMap").EnumerateArray().Select(value => value.GetByte()).ToArray(),
                payload.TryGetProperty("serviceId", out var serviceId) ? serviceId.GetGuid() : null),
            "hid-stop" => await hid.Stop(),
            "hid-claim" => hid.Claim(payload.GetProperty("nonce").GetString()!),
            "hid-status" => hid.Status(),
            "hid-send" => await hid.Send(payload.GetProperty("host").GetString()!, payload.GetProperty("reports")),
            _ => throw new InvalidOperationException(),
        };
        Console.WriteLine(JsonSerializer.Serialize(new { replyTo = id, result }));
    }
    catch
    {
        Console.WriteLine(JsonSerializer.Serialize(new { replyTo = id, error = "蓝牙键鼠操作失败" }));
    }
}
await hid.Stop();

sealed class BluetoothHid
{
    private readonly object gate = new();
    private readonly HashSet<string> needsReset = new();
    private readonly HashSet<string> dirty = new();
    private GattServiceProvider? provider, rendezvous;
    private readonly Dictionary<string, (string Host, long Expires)> challenges = new();
    private static readonly Guid Challenge = new("b3e14843-f7ab-4b94-a10c-c5b2408f2432");
    private GattLocalCharacteristic[] reports = [];
    private bool? adapterPresent, peripheralSupported;
    private string reason = "";

    private static Guid Uuid(int id) => Guid.Parse($"0000{id:x4}-0000-1000-8000-00805f9b34fb");

    private static bool Advertising(GattServiceProvider? service) =>
        service?.AdvertisementStatus is GattServiceProviderAdvertisementStatus.Started
            or GattServiceProviderAdvertisementStatus.StartedWithoutAllAdvertisementData;

    public async Task<object> Start(byte[] map, Guid? serviceId)
    {
        if (Advertising(provider))
        {
            if (rendezvous == null && serviceId.HasValue) await PublishRendezvous(serviceId.Value);
            return Status();
        }
        await Stop();
        var adapter = await BluetoothAdapter.GetDefaultAsync();
        adapterPresent = adapter != null;
        peripheralSupported = adapter?.IsPeripheralRoleSupported ?? false;
        if (peripheralSupported != true) return Status();
        var created = await GattServiceProvider.CreateAsync(Uuid(0x1812));
        if (created.Error != BluetoothError.Success)
        {
            reason = created.Error.ToString();
            return Status();
        }
        var local = created.ServiceProvider;
        await Characteristic(local, 0x2a4a, GattCharacteristicProperties.Read, [0x11, 0x01, 0, 0x02]);
        await Characteristic(local, 0x2a4b, GattCharacteristicProperties.Read, map);
        var control = await Characteristic(local, 0x2a4c, GattCharacteristicProperties.WriteWithoutResponse);
        control.WriteRequested += async (_, args) =>
        {
            var deferral = args.GetDeferral();
            try { await args.GetRequestAsync(); } catch { } finally { deferral.Complete(); }
        };
        var localReports = new GattLocalCharacteristic[2];
        for (var index = 0; index < localReports.Length; index++)
        {
            int length = index == 0 ? 4 : 8;
            var report = await Characteristic(local, 0x2a4d, GattCharacteristicProperties.Read | GattCharacteristicProperties.Notify);
            var descriptor = await report.CreateDescriptorAsync(Uuid(0x2908), new GattLocalDescriptorParameters
            {
                ReadProtectionLevel = GattProtectionLevel.EncryptionRequired,
                StaticValue = CryptographicBuffer.CreateFromByteArray([(byte)(index + 1), 1]),
            });
            if (descriptor.Error != BluetoothError.Success) throw new InvalidOperationException();
            report.ReadRequested += async (_, args) =>
            {
                var deferral = args.GetDeferral();
                try
                {
                    var request = await args.GetRequestAsync();
                    request?.RespondWithValue(CryptographicBuffer.CreateFromByteArray(new byte[length]));
                }
                catch { }
                finally { deferral.Complete(); }
            };
            localReports[index] = report;
        }
        reports = localReports;
        provider = local;
        reason = "";
        provider.StartAdvertising(new GattServiceProviderAdvertisingParameters { IsConnectable = true, IsDiscoverable = true });
        if (serviceId.HasValue) await PublishRendezvous(serviceId.Value);
        return Status();
    }

    private async Task PublishRendezvous(Guid serviceId)
    {
        var result = await GattServiceProvider.CreateAsync(serviceId);
        if (result.Error != BluetoothError.Success) return;
        var local = result.ServiceProvider;
        var characteristic = await local.Service.CreateCharacteristicAsync(Challenge, new GattLocalCharacteristicParameters
        {
            CharacteristicProperties = GattCharacteristicProperties.Read,
        });
        if (characteristic.Error != BluetoothError.Success) return;
        characteristic.Characteristic.ReadRequested += async (_, args) =>
        {
            var deferral = args.GetDeferral();
            try
            {
                var request = await args.GetRequestAsync();
                if (request == null) return;
                if (request.Offset != 0) { request.RespondWithProtocolError(0x07); return; }
                var data = RandomNumberGenerator.GetBytes(16);
                var nonce = Convert.ToHexString(data).ToLowerInvariant();
                string host = args.Session.DeviceId.Id;
                lock (gate)
                {
                    if (!ReferenceEquals(rendezvous, local)) return;
                    long now = Environment.TickCount64;
                    foreach (var key in challenges.Where(entry => entry.Value.Expires <= now || entry.Value.Host == host).Select(entry => entry.Key).ToArray())
                        challenges.Remove(key);
                    if (challenges.Count >= 64) { request.RespondWithProtocolError(0x11); return; }
                    challenges[nonce] = (host, now + 12000);
                }
                request.RespondWithValue(CryptographicBuffer.CreateFromByteArray(data));
            }
            catch { }
            finally { deferral.Complete(); }
        };
        lock (gate) rendezvous = local;
        local.StartAdvertising(new GattServiceProviderAdvertisingParameters { IsConnectable = true, IsDiscoverable = true });
    }

    public object Claim(string nonce)
    {
        lock (gate)
        {
            if (!challenges.Remove(nonce, out var challenge) || challenge.Expires <= Environment.TickCount64)
                throw new InvalidOperationException();
            return new { host = challenge.Host };
        }
    }

    private static async Task<GattLocalCharacteristic> Characteristic(GattServiceProvider local, int id, GattCharacteristicProperties properties, byte[]? value = null)
    {
        var parameters = new GattLocalCharacteristicParameters
        {
            CharacteristicProperties = properties,
            ReadProtectionLevel = GattProtectionLevel.EncryptionRequired,
            WriteProtectionLevel = GattProtectionLevel.EncryptionRequired,
        };
        if (value != null) parameters.StaticValue = CryptographicBuffer.CreateFromByteArray(value);
        var created = await local.Service.CreateCharacteristicAsync(Uuid(id), parameters);
        if (created.Error != BluetoothError.Success) throw new InvalidOperationException();
        return created.Characteristic;
    }

    private static bool Connected(GattSubscribedClient client) => client.Session.SessionStatus == GattSessionStatus.Active;
    private GattSubscribedClient? Client(int report, string host) =>
        reports[report].SubscribedClients.FirstOrDefault(client => Connected(client) && client.Session.DeviceId.Id == host);

    public object Status()
    {
        var hosts = reports.SelectMany(report => report.SubscribedClients)
            .Where(Connected).Select(client => client.Session.DeviceId.Id).Distinct()
            .Select(id =>
            {
                bool subscribed = Client(0, id) != null && Client(1, id) != null;
                lock (gate) return new { id, hostConnected = true, reportSubscribed = subscribed, ready = subscribed };
            }).ToArray();
        var advertising = Advertising(provider);
        return new { adapterPresent, peripheralSupported, hidServiceReady = advertising, advertising,
            rendezvousReady = advertising && Advertising(rendezvous), reason = provider?.AdvertisementStatus.ToString() ?? reason, hosts };
    }

    private static async Task Notify(GattLocalCharacteristic report, GattSubscribedClient client, byte[] bytes)
    {
        var status = await report.NotifyValueAsync(CryptographicBuffer.CreateFromByteArray(bytes), client);
        if (status.Status != GattCommunicationStatus.Success) throw new InvalidOperationException();
    }

    public async Task<object> Send(string host, JsonElement payload)
    {
        if (reports.Length != 2 || !Advertising(provider))
            throw new InvalidOperationException();
        var packets = payload.EnumerateArray().Select(packet =>
        {
            int index = packet[0].GetInt32() - 1;
            var bytes = packet[1].EnumerateArray().Select(value => value.GetByte()).ToArray();
            if (index is < 0 or > 1 || bytes.Length != (index == 0 ? 4 : 8)) throw new InvalidOperationException();
            return (index, bytes);
        }).ToArray();
        if (packets.Length > 256) throw new InvalidOperationException();
        bool neutral = packets.Length == 2 && packets[0].index != packets[1].index && packets.All(packet => packet.bytes.All(b => b == 0));
        try
        {
            lock (gate)
            {
                if (needsReset.Contains(host) && !neutral) throw new InvalidOperationException();
                dirty.Add(host);
            }
            foreach (var (index, bytes) in packets)
            {
                var client = Client(index, host) ?? throw new InvalidOperationException();
                await Notify(reports[index], client, bytes);
            }
            if (neutral) lock (gate) { dirty.Remove(host); needsReset.Remove(host); }
            return new { };
        }
        catch
        {
            lock (gate) needsReset.Add(host);
            throw;
        }
    }

    public async Task<object> Stop()
    {
        string[] hosts;
        lock (gate) hosts = dirty.ToArray();
        foreach (var host in hosts)
        {
            for (int index = 0; index < reports.Length; index++)
            {
                try
                {
                    if (Client(index, host) is { } client) await Notify(reports[index], client, new byte[index == 0 ? 4 : 8]);
                }
                catch { }
            }
        }
        provider?.StopAdvertising();
        provider = null;
        reports = [];
        GattServiceProvider? previous;
        lock (gate)
        {
            previous = rendezvous;
            rendezvous = null;
            challenges.Clear();
            dirty.Clear();
            needsReset.Clear();
        }
        previous?.StopAdvertising();
        return new { };
    }
}
