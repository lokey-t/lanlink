using System;
using System.Collections;
using System.Collections.Generic;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;

namespace LanLink.Desktop {
  public sealed class PairingPanel : Border {
    readonly Client client;
    readonly Func<bool, bool> confirm;
    readonly Func<Task> joined;
    readonly DispatcherTimer timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(250) };
    readonly System.Diagnostics.Stopwatch elapsed = new System.Diagnostics.Stopwatch();
    readonly TextBlock code = MainWindow.Text("···· ····", 36, "#354CC2");
    readonly TextBlock countdown = MainWindow.LText("正在生成配对码…", 12, "#7B879D");
    readonly TextBlock status = MainWindow.Text("", 12, "#71819A");
    readonly Border progress = new Border { Background = MainWindow.Brush("#798CEC"), CornerRadius = new CornerRadius(3), HorizontalAlignment = HorizontalAlignment.Left };
    readonly Image qr = new Image { Width = 168, Height = 168 };
    readonly Border qrFrame;
    readonly TextBox input = new TextBox { MaxLength = 8, FontSize = 24, FontFamily = new FontFamily("Consolas"), MinHeight = 52, Margin = new Thickness(0, 8, 0, 14) };
    readonly Button reveal, joinButton, copyCode;
    bool showing, loading, closed, joining, groupEstablished, waitingConnection, completed;
    HashSet<string> initialPeers;
    double remaining, duration = 60000;
    DateTime nextFetch;
    public string CurrentCode { get; private set; }
    public bool QRVisible { get { return qrFrame.IsVisible; } }
    public bool JoiningVisible { get { return input.IsVisible; } }
    public Task InitialLoad { get; private set; }
    public PairingPanel(Window owner, Client client, Func<bool, bool> confirm, Func<Task> joined) {
      this.client = client; this.confirm = confirm; this.joined = joined;
      Resources = owner.Resources;
      MaxWidth = 900; HorizontalAlignment = HorizontalAlignment.Stretch;
      var panel = new StackPanel();
      var hint = MainWindow.LText("让两台设备连接同一局域网，并打开 LanLink。", 14, "#63728C");
      hint.Margin = new Thickness(0, 0, 0, 16);
      panel.Children.Add(hint);
      status.TextWrapping = TextWrapping.Wrap;
      status.TextTrimming = TextTrimming.None;
      System.Windows.Automation.AutomationProperties.SetLiveSetting(status, System.Windows.Automation.AutomationLiveSetting.Polite);
      panel.Children.Add(status);

      var columns = new Grid();
      columns.ColumnDefinitions.Add(new ColumnDefinition());
      columns.ColumnDefinitions.Add(new ColumnDefinition());
      columns.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
      columns.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
      panel.Children.Add(columns);
      var share = new StackPanel();
      var shareCard = Card(share, "#F2F5FC");
      columns.Children.Add(shareCard);
      var joinPanel = new StackPanel();
      var joinCard = Card(joinPanel, "#FFFFFF");
      columns.Children.Add(joinCard);
      columns.SizeChanged += delegate {
        bool stacked = columns.ActualWidth < 550;
        columns.ColumnDefinitions[1].Width = stacked ? new GridLength(0) : new GridLength(1, GridUnitType.Star);
        Grid.SetRow(joinCard, stacked ? 1 : 0);
        Grid.SetColumn(joinCard, stacked ? 0 : 1);
        shareCard.Margin = stacked ? new Thickness(0, 0, 0, 14) : new Thickness(0, 0, 8, 0);
        joinCard.Margin = stacked ? new Thickness(0) : new Thickness(8, 0, 0, 0);
      };
      AddCardHeading(share, "分享本机配对码", "layout", "让对方在 LanLink 扫码，或输入下方配对码。");
      code.FontFamily = new FontFamily("Consolas");
      code.FontWeight = FontWeights.SemiBold;
      code.Margin = new Thickness(0, 14, 0, 8);
      share.Children.Add(code);
      var track = new Border { Height = 4, CornerRadius = new CornerRadius(3), Background = MainWindow.Brush("#E1E8F7"), Child = progress };
      track.SizeChanged += delegate { UpdateCountdown(); };
      share.Children.Add(track);
      countdown.Margin = new Thickness(0, 8, 0, 12);
      share.Children.Add(countdown);
      var actions = new WrapPanel();
      copyCode = MainWindow.LButton("复制配对码", delegate {
        try {
          if (CurrentCode == null) throw new Exception(Locale.T("配对码正在刷新，请稍后再试。"));
          Clipboard.SetText(CurrentCode);
          status.Text = Locale.T("已复制八位配对码，可直接粘贴到另一台设备。");
        } catch (Exception e) { status.Text = MainWindow.DisplayError(e.Message); }
      });
      copyCode.IsEnabled = false;
      copyCode.Margin = new Thickness(0, 4, 8, 0);
      actions.Children.Add(copyCode);
      reveal = MainWindow.LButton("点击查看二维码", () => { var task = ToggleQR(); });
      reveal.Margin = new Thickness(0, 4, 0, 0);
      actions.Children.Add(reveal);
      share.Children.Add(actions);
      RenderOptions.SetBitmapScalingMode(qr, BitmapScalingMode.NearestNeighbor);
      qrFrame = new Border { Child = qr, Padding = new Thickness(10), Background = Brushes.White,
        CornerRadius = new CornerRadius(12), Visibility = Visibility.Collapsed,
        HorizontalAlignment = HorizontalAlignment.Left, Margin = new Thickness(0, 14, 0, 0) };
      share.Children.Add(qrFrame);

      AddCardHeading(joinPanel, "输入对方配对码", "keyboard", "输入对方「添加设备」页面的八位数字");
      var inputLabel = MainWindow.LText("八位数字配对码", 12, "#63728C");
      inputLabel.Margin = new Thickness(0, 16, 0, 0);
      joinPanel.Children.Add(inputLabel);
      System.Windows.Automation.AutomationProperties.SetName(input, Locale.T("其他设备的八位数字配对码")); input.PreviewTextInput += delegate(object sender, TextCompositionEventArgs e) { e.Handled = !Regex.IsMatch(e.Text, "^[0-9]+$"); if(e.Handled)status.Text=Locale.T("配对码只能包含八位数字，可粘贴带空格的分组码。"); };
      DataObject.AddPastingHandler(input, delegate(object sender, DataObjectPastingEventArgs e) {
        e.CancelCommand(); string value=NormalizeCode(e.DataObject.GetData(typeof(string)) as string);
        if(value==null){string pasted=e.DataObject.GetData(typeof(string)) as string;status.Text=pasted!=null && Regex.IsMatch(Regex.Replace(pasted,@"\s",""),"^[0-9]+$")?Locale.T("配对码需要八位数字，请检查长度后重试。"):Locale.T("配对码只能包含数字和分组空格，请检查后重试。");return;}
        string next=input.Text.Remove(input.SelectionStart,input.SelectionLength).Insert(input.SelectionStart,value);
        if(next.Length>8 || next.Length==0){status.Text=Locale.T("请输入完整的八位数字配对码；粘贴前可先全选原内容。");return;}
        int caret=input.SelectionStart+value.Length;input.Text=next;input.CaretIndex=caret;status.Text=next.Length==8?Locale.T("配对码已填入，请点击配对并加入。"):Locale.T("请补全八位数字配对码。");
      });
      input.KeyDown += async delegate(object sender, KeyEventArgs e) {
        if (e.Key != Key.Enter) return;
        e.Handled = true;
        await Join();
      };
      joinPanel.Children.Add(input);
      joinButton = MainWindow.LButton("配对并加入", () => { var task = Join(); });
      joinButton.Style = (Style)FindResource("Primary");
      joinButton.HorizontalAlignment = HorizontalAlignment.Left;
      joinPanel.Children.Add(joinButton);
      var note = MainWindow.LText("双方已有分组会一并合并。", 12, "#63728C");
      note.Margin = new Thickness(0, 12, 0, 0);
      joinPanel.Children.Add(note);
      var manual = new StackPanel { Margin = new Thickness(0, 10, 0, 0) };
      var address = new TextBox { Margin = new Thickness(0, 0, 0, 8) }; System.Windows.Automation.AutomationProperties.SetName(address, Locale.T("已配对设备的局域网 IP"));
      manual.Children.Add(MainWindow.LText("输入已配对设备的局域网 IP", 12, "#7B879D")); manual.Children.Add(address);
      var connect = MainWindow.LButton("手动连接", delegate { }); connect.Click += async delegate {
        if(!connect.IsEnabled)return;connect.IsEnabled=false;address.IsEnabled=false;status.Text=Locale.T("正在连接设备并验证身份…");
        try { var result=await client.Call("connect", new { host = address.Text.Trim(), port = 45831 }); status.Text=string.IsNullOrWhiteSpace(Json.Text(result,"message"))?Locale.T("设备已连接。"):Json.Text(result,"message"); }
        catch (Exception e) { status.Text = MainWindow.DisplayError(e.Message)+Locale.T(" 可检查地址后重试。"); }
        finally {connect.IsEnabled=true;address.IsEnabled=true;}
      }; manual.Children.Add(connect);
      panel.Children.Add(new Expander { Header=UiLocale.Content("已配对设备无法自动连接？"), Content = manual, FontSize = 12, Foreground = MainWindow.Brush("#8390A5"), Margin = new Thickness(0, 12, 0, 0) });
      Child = panel;
      InitialLoad = Task.FromResult(0);
      IsVisibleChanged += async delegate {
        closed = !IsVisible;
        if (!closed) { initialPeers=null;completed=false;waitingConnection=false;InitialLoad = RefreshCode(); timer.Start(); }
        else { timer.Stop(); SetQRShown(false); CurrentCode=null; copyCode.IsEnabled=false; code.Text="···· ····"; remaining=0; try { await client.Call("pairing-stop"); } catch {} }
      };
      timer.Tick += async delegate { UpdateCountdown(); if (DateTime.UtcNow >= nextFetch) await RefreshCode(); };

    }
    static Border Card(StackPanel content, string background) {
      return new Border { Child = content, Background = MainWindow.Brush(background), BorderBrush = MainWindow.Brush("#DDE5F2"),
        BorderThickness = new Thickness(1), CornerRadius = new CornerRadius(16), Padding = new Thickness(20) };
    }
    static void AddCardHeading(StackPanel card, string title, string icon, string description) {
      var heading = new Grid();
      heading.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(30) });
      heading.ColumnDefinitions.Add(new ColumnDefinition());
      var glyph = WindowShell.Glyph(icon, 20);
      glyph.HorizontalAlignment = HorizontalAlignment.Left;
      heading.Children.Add(glyph);
      var label = MainWindow.LText(title, 17);
      label.FontWeight = FontWeights.SemiBold;
      Grid.SetColumn(label, 1);
      heading.Children.Add(label);
      card.Children.Add(heading);
      var hint = MainWindow.LText(description, 13, "#63728C");
      hint.Margin = new Thickness(0, 8, 0, 0);
      card.Children.Add(hint);
    }
    internal static string NormalizeCode(string value) { return UiText.PairingCode(value); }
    void UpdateJoinLabel() { joinButton.Content = groupEstablished ? Locale.T("配对并合并设备组") : Locale.T("配对并加入"); }
    void SetQRShown(bool value) {
      showing = value;
      qrFrame.Visibility = Visibility.Collapsed;
      qr.Source = null;
      reveal.Content = UiLocale.Content(value ? "隐藏二维码" : "点击查看二维码");
    }
    public async Task ToggleQR() { SetQRShown(!showing); await RefreshCode(); }
    void UpdateCountdown() {
      var left = Math.Max(0, remaining - elapsed.Elapsed.TotalMilliseconds); var track = progress.Parent as Border;
      if (track != null) progress.Width = Math.Max(0, track.ActualWidth * Math.Min(1, left / duration));
      countdown.Text = left > 0 ? Math.Ceiling(left / 1000) + Locale.T(" 秒后刷新 · 每分钟自动更换") : Locale.T("正在刷新配对码…");
      if (left <= 0) { code.Text = "···· ····"; CurrentCode = null; copyCode.IsEnabled=false; qr.Source = null; qrFrame.Visibility = Visibility.Collapsed; }
    }
    internal async Task<bool> ObserveConnections(object state) {
      var online=new HashSet<string>();foreach(var peer in Json.Get(state,"peers") as IEnumerable ?? new object[0])online.Add(Json.Text(peer,"id"));
      if(initialPeers==null)initialPeers=online;
      else if(!completed && (waitingConnection ? online.Count>0 : !online.IsSubsetOf(initialPeers))) {completed=true;await joined();return true;}
      return false;
    }
    public async Task RefreshCode() {
      if (loading || closed || joining) return; loading = true; reveal.IsEnabled = false;
      try {
        var state = await client.Call("state"); if(closed)return;
        if(await ObserveConnections(state))return;
        var result = await client.Call("pairing", new { reveal = showing }); if (closed) return;
        groupEstablished = Json.Flag(result, "groupEstablished"); UpdateJoinLabel();
        CurrentCode = Json.Text(result, "code"); if (!Regex.IsMatch(CurrentCode, "^[0-9]{8}$")) throw new Exception(Locale.T("配对码暂不可用"));
        copyCode.IsEnabled=true; code.Text = CurrentCode.Substring(0, 4) + " " + CurrentCode.Substring(4); remaining = Json.Number(result, "remainingMs", 0); duration = Json.Number(result, "durationMs", 60000); elapsed.Restart();
        if (showing) { string data = Json.Text(result, "qr"); qr.Source = MainWindow.DecodeImage(data.Substring(data.IndexOf(',') + 1)); qrFrame.Visibility = Visibility.Visible; }
        UpdateCountdown();
      } catch (Exception e) { CurrentCode = null; remaining = 0; UpdateCountdown(); copyCode.IsEnabled=false; status.Text = Locale.T("无法刷新配对码：") + MainWindow.DisplayError(e.Message); }
      finally { loading = false; reveal.IsEnabled = true; nextFetch = DateTime.UtcNow.AddSeconds(1); }
    }
    public async Task Join() {
      if (joining || waitingConnection) return; if (!Regex.IsMatch(input.Text, "^[0-9]{8}$")) { status.Text = Locale.T("请输入完整的八位数字配对码。"); return; }
      joining = true; joinButton.IsEnabled = false; input.IsEnabled = false;
      try { var state = await client.Call("state"); groupEstablished = Json.Flag(state, "groupEstablished"); UpdateJoinLabel(); if (!confirm(groupEstablished)) return; status.Text = Locale.T("正在查找设备并配对…"); await client.Call("join", new { code = input.Text, expectedGroup = Json.Text(state, "groupId") }); waitingConnection=true; initialPeers=new HashSet<string>(); status.Text=Locale.T("已配对，正在同步组内设备…"); input.Text = ""; SetQRShown(false); }
      catch (Exception e) { status.Text = MainWindow.DisplayError(e.Message); }
      finally { joining = false; joinButton.IsEnabled = !waitingConnection; input.IsEnabled = !waitingConnection; }
    }
  }
}
