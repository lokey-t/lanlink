using System.Globalization;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Devices.Enumeration;
using Windows.Security.Cryptography;

static class HidConnection
{
    private static readonly Guid ChallengeId = new("b3e14843-f7ab-4b94-a10c-c5b2408f2432");

    public static async Task<object> Find(Guid serviceId)
    {
        var watcher = new BluetoothLEAdvertisementWatcher { ScanningMode = BluetoothLEScanningMode.Active };
        watcher.AdvertisementFilter.Advertisement.ServiceUuids.Add(serviceId);
        var found = new TaskCompletionSource<(ulong Address, BluetoothAddressType Type)>(TaskCreationOptions.RunContinuationsAsynchronously);
        watcher.Received += (_, advertisement) => found.TrySetResult((advertisement.BluetoothAddress, advertisement.BluetoothAddressType));
        watcher.Stopped += (_, _) => found.TrySetCanceled();
        watcher.Start();
        (ulong address, BluetoothAddressType type) target;
        try { target = await found.Task.WaitAsync(TimeSpan.FromSeconds(7)); }
        finally { watcher.Stop(); }

        using var device = await BluetoothLEDevice.FromBluetoothAddressAsync(target.address, target.type);
        if (device == null) throw new InvalidOperationException();
        var services = await device.GetGattServicesForUuidAsync(serviceId, BluetoothCacheMode.Uncached);
        if (services.Status != GattCommunicationStatus.Success || services.Services.Count != 1)
            throw new InvalidOperationException();
        using var service = services.Services[0];
        var characteristics = await service.GetCharacteristicsForUuidAsync(ChallengeId);
        if (characteristics.Status != GattCommunicationStatus.Success || characteristics.Characteristics.Count != 1)
            throw new InvalidOperationException();
        var challenge = await characteristics.Characteristics[0].ReadValueAsync(BluetoothCacheMode.Uncached);
        if (challenge.Status != GattCommunicationStatus.Success || challenge.Value.Length != 16)
            throw new InvalidOperationException();
        CryptographicBuffer.CopyToByteArray(challenge.Value, out var bytes);
        return new { address = target.address.ToString("x12", CultureInfo.InvariantCulture), addressType = (int)target.type, nonce = Convert.ToHexString(bytes).ToLowerInvariant() };
    }

    public static async Task<object> Pair(ulong address, BluetoothAddressType type)
    {
        using var device = await BluetoothLEDevice.FromBluetoothAddressAsync(address, type);
        if (device == null) throw new InvalidOperationException();
        var pairing = device.DeviceInformation.Pairing;
        var result = pairing.IsPaired ? DevicePairingResultStatus.AlreadyPaired : (await pairing.PairAsync()).Status;
        return new { paired = result is DevicePairingResultStatus.Paired or DevicePairingResultStatus.AlreadyPaired };
    }
}
