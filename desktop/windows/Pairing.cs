using System;
using System.Collections;
using System.Collections.Generic;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Shapes;
using System.Windows.Threading;

namespace LanLink.Desktop {
  public sealed class PairingPanel : Border {
    readonly Client client;
    readonly Func<bool, bool> confirm;
    readonly Func<Task> joined;
    readonly DispatcherTimer timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(250) };
    readonly System.Diagnostics.Stopwatch elapsed = new System.Diagnostics.Stopwatch();
    readonly TextBlock code = MainWindow.Text("···· ····", 34, "#354CC2");
    readonly TextBlock countdown = MainWindow.LText("正在生成配对码…", 12, "#7B879D");
    readonly TextBlock status = MainWindow.Text("", 12, "#71819A");
    readonly Border progress = new Border { Background = MainWindow.Brush("#798CEC"), CornerRadius = new CornerRadius(3), HorizontalAlignment = HorizontalAlignment.Left };
    readonly Image qr = new Image { Width = 190, Height = 190, Visibility = Visibility.Collapsed };
    readonly Grid placeholder = new Grid { Width = 190, Height = 190 };
    readonly StackPanel joinPanel = new StackPanel { Visibility = Visibility.Collapsed, Margin = new Thickness(0, 14, 0, 0) };
    readonly TextBox input = new TextBox { MaxLength = 8, FontSize = 20, FontFamily = new FontFamily("Consolas"), Margin = new Thickness(0, 8, 0, 10) };
    readonly Button reveal, joinButton, joinOption, copyCode;
    bool showing, loading, closed, joining, groupEstablished, waitingConnection, completed;
    HashSet<string> initialPeers;
    double remaining, duration = 60000;
    DateTime nextFetch;
    public string CurrentCode { get; private set; }
    public bool QRVisible { get { return qr.Visibility == Visibility.Visible; } }
    public bool JoiningVisible { get { return joinPanel.Visibility == Visibility.Visible; } }
    public Task InitialLoad { get; private set; }
    public PairingPanel(Window owner, Client client, Func<bool, bool> confirm, Func<Task> joined) {
      this.client = client; this.confirm = confirm; this.joined = joined;
      Resources = owner.Resources;
      Padding = new Thickness(24); Background = MainWindow.Brush("#FFFFFF"); CornerRadius = new CornerRadius(20);
      var panel = MainWindow.Column(); panel.Children.Add(MainWindow.LText("让设备相遇", 25));
      var hint = MainWindow.LText("扫码或输入配对码，双方已有设备将自动合并为一组。", 13, "#7B879D"); hint.Margin = new Thickness(0, 9, 0, 24); panel.Children.Add(hint);
      var columns = new Grid(); columns.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(246) }); columns.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(28) }); columns.ColumnDefinitions.Add(new ColumnDefinition()); panel.Children.Add(columns);
      var qrPanel = new StackPanel(); var qrArea = new Grid { Height = 212 }; qrArea.Children.Add(placeholder); qrArea.Children.Add(qr); qrPanel.Children.Add(qrArea);
      // A neutral skeleton, never a blurred real credential before the user reveals it.
      var skeleton = new Canvas { Width = 164, Height = 164, Opacity = .65, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
      foreach (var p in new[] { new Point(0, 0), new Point(114, 0), new Point(0, 114) }) {
        var marker = new Border { Width = 50, Height = 50, CornerRadius = new CornerRadius(12), BorderBrush = MainWindow.Brush("#C5D0E8"), BorderThickness = new Thickness(9) }; Canvas.SetLeft(marker, p.X); Canvas.SetTop(marker, p.Y); skeleton.Children.Add(marker);
      }
      foreach (var p in new[] { new Point(69, 4), new Point(4, 72), new Point(72, 70), new Point(116, 116), new Point(70, 140), new Point(141, 74) }) {
        var dot = new Border { Width = 20, Height = 20, CornerRadius = new CornerRadius(5), Background = MainWindow.Brush("#DAE2F1") }; Canvas.SetLeft(dot, p.X); Canvas.SetTop(dot, p.Y); skeleton.Children.Add(dot);
      }
      placeholder.Children.Add(skeleton);
      reveal = MainWindow.LButton("点击查看二维码", () => { var task = ToggleQR(); }); reveal.HorizontalAlignment = HorizontalAlignment.Center; reveal.Margin = new Thickness(0, 4, 0, 8); qrPanel.Children.Add(reveal);
      var qrHint = MainWindow.LText("二维码与数字码同步刷新", 11, "#8C98AC"); qrHint.HorizontalAlignment = HorizontalAlignment.Center; qrPanel.Children.Add(qrHint);
      var qrCard = new Border { Child = qrPanel, Padding = new Thickness(12, 8, 12, 16), Background = MainWindow.Brush("#F2F5FC"), CornerRadius = new CornerRadius(22) }; columns.Children.Add(qrCard);
      var right = new StackPanel { VerticalAlignment = VerticalAlignment.Center }; Grid.SetColumn(right, 2); columns.Children.Add(right);
      joinOption = MainWindow.LButton("加入其他组 →", ToggleJoin); joinOption.BorderThickness = new Thickness(0); joinOption.Background = Brushes.Transparent; joinOption.Foreground = MainWindow.Brush("#536AD0"); joinOption.Padding = new Thickness(0, 10, 0, 5); joinOption.HorizontalAlignment = HorizontalAlignment.Left;
      right.Children.Add(MainWindow.LText("其他设备加入本组", 16));
      code.FontFamily = new FontFamily("Consolas"); code.FontWeight = FontWeights.SemiBold; code.Margin = new Thickness(0, 13, 0, 14); right.Children.Add(code);
      copyCode = MainWindow.LButton("复制配对码", delegate { try { if(CurrentCode==null)throw new Exception(Locale.T("配对码正在刷新，请稍后再试。")); Clipboard.SetText(CurrentCode); status.Text=Locale.T("已复制八位配对码，可直接粘贴到另一台设备。"); } catch(Exception e) { status.Text=MainWindow.DisplayError(e.Message); } }); copyCode.IsEnabled=false; copyCode.HorizontalAlignment=HorizontalAlignment.Left; copyCode.Margin=new Thickness(0,0,0,12);right.Children.Add(copyCode);
      var track = new Border { Height = 5, CornerRadius = new CornerRadius(3), Background = MainWindow.Brush("#E6EBF6"), Child = progress }; track.SizeChanged += delegate { UpdateCountdown(); }; right.Children.Add(track);
      countdown.Margin = new Thickness(0, 8, 0, 14); right.Children.Add(countdown);
      var note = MainWindow.LText("让另一台设备扫码，或输入本机八位配对码。\n双方已有分组会一并合并。", 12, "#8390A5"); note.LineHeight = 21; right.Children.Add(note); right.Children.Add(joinOption);
      joinPanel.Children.Add(MainWindow.LText("输入其他设备的八位配对码", 12, "#63728C"));
      System.Windows.Automation.AutomationProperties.SetName(input, Locale.T("其他设备的八位数字配对码")); input.PreviewTextInput += delegate(object sender, TextCompositionEventArgs e) { e.Handled = !Regex.IsMatch(e.Text, "^[0-9]+$"); if(e.Handled)status.Text=Locale.T("配对码只能包含八位数字，可粘贴带空格的分组码。"); };
      DataObject.AddPastingHandler(input, delegate(object sender, DataObjectPastingEventArgs e) {
        e.CancelCommand(); string value=NormalizeCode(e.DataObject.GetData(typeof(string)) as string);
        if(value==null){string pasted=e.DataObject.GetData(typeof(string)) as string;status.Text=pasted!=null && Regex.IsMatch(Regex.Replace(pasted,@"\s",""),"^[0-9]+$")?Locale.T("配对码需要八位数字，请检查长度后重试。"):Locale.T("配对码只能包含数字和分组空格，请检查后重试。");return;}
        string next=input.Text.Remove(input.SelectionStart,input.SelectionLength).Insert(input.SelectionStart,value);
        if(next.Length>8 || next.Length==0){status.Text=Locale.T("请输入完整的八位数字配对码；粘贴前可先全选原内容。");return;}
        int caret=input.SelectionStart+value.Length;input.Text=next;input.CaretIndex=caret;status.Text=next.Length==8?Locale.T("配对码已填入，请点击配对并加入。"):Locale.T("请补全八位数字配对码。");
      });
      joinPanel.Children.Add(input); joinButton = MainWindow.LButton("配对并加入", () => { var task = Join(); }); joinButton.Style = (Style)FindResource("Primary"); joinPanel.Children.Add(joinButton); right.Children.Add(joinPanel);
      status.TextWrapping = TextWrapping.Wrap; status.Margin = new Thickness(0, 20, 0, 0); panel.Children.Add(status);
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
        else { timer.Stop(); showing = false; qr.Source = null; qr.Visibility=Visibility.Collapsed; placeholder.Visibility=Visibility.Visible; reveal.Content=UiLocale.Content("点击查看二维码"); CurrentCode=null; copyCode.IsEnabled=false; code.Text="···· ····"; remaining=0; try { await client.Call("pairing-stop"); } catch {} }
      };
      timer.Tick += async delegate { UpdateCountdown(); if (DateTime.UtcNow >= nextFetch) await RefreshCode(); };

    }
    internal static string NormalizeCode(string value) { return UiText.PairingCode(value); }
    void UpdateJoinLabel() { joinOption.Content = JoiningVisible ? Locale.T("收起输入 ↑") : groupEstablished ? Locale.T("配对并合并设备组 →") : Locale.T("加入其他组 →"); joinButton.Content = groupEstablished ? Locale.T("配对并合并设备组") : Locale.T("配对并加入"); }
    public void ToggleJoin() { joinPanel.Visibility = JoiningVisible ? Visibility.Collapsed : Visibility.Visible; UpdateJoinLabel(); if (JoiningVisible) input.Focus(); }
    public async Task ToggleQR() { showing = !showing; qr.Visibility = Visibility.Collapsed; placeholder.Visibility = Visibility.Visible; if (!showing) qr.Source = null; reveal.Content = showing ? Locale.T("隐藏二维码") : Locale.T("点击查看二维码"); await RefreshCode(); }
    void UpdateCountdown() {
      var left = Math.Max(0, remaining - elapsed.Elapsed.TotalMilliseconds); var track = progress.Parent as Border;
      if (track != null) progress.Width = Math.Max(0, track.ActualWidth * Math.Min(1, left / duration));
      countdown.Text = left > 0 ? Math.Ceiling(left / 1000) + Locale.T(" 秒后刷新 · 每分钟自动更换") : Locale.T("正在刷新配对码…");
      if (left <= 0) { code.Text = "···· ····"; CurrentCode = null; copyCode.IsEnabled=false; qr.Source = null; qr.Visibility = Visibility.Collapsed; placeholder.Visibility = Visibility.Visible; }
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
        if (showing) { string data = Json.Text(result, "qr"); qr.Source = MainWindow.DecodeImage(data.Substring(data.IndexOf(',') + 1)); qr.Visibility = Visibility.Visible; placeholder.Visibility = Visibility.Collapsed; }
        UpdateCountdown();
      } catch (Exception e) { CurrentCode = null; remaining = 0; UpdateCountdown(); copyCode.IsEnabled=false; status.Text = Locale.T("无法刷新配对码：") + MainWindow.DisplayError(e.Message); }
      finally { loading = false; reveal.IsEnabled = true; nextFetch = DateTime.UtcNow.AddSeconds(1); }
    }
    public async Task Join() {
      if (joining) return; if (!Regex.IsMatch(input.Text, "^[0-9]{8}$")) { status.Text = Locale.T("请输入完整的八位数字配对码。"); return; }
      joining = true; joinButton.IsEnabled = false; input.IsEnabled = false;
      try { var state = await client.Call("state"); groupEstablished = Json.Flag(state, "groupEstablished"); UpdateJoinLabel(); if (!confirm(groupEstablished)) return; status.Text = Locale.T("正在查找设备并配对…"); await client.Call("join", new { code = input.Text, expectedGroup = Json.Text(state, "groupId") }); waitingConnection=true; initialPeers=new HashSet<string>(); status.Text=Locale.T("已配对，正在同步组内设备…"); input.Text = ""; joinPanel.Visibility = Visibility.Collapsed; showing = false; }
      catch (Exception e) { status.Text = MainWindow.DisplayError(e.Message); }
      finally { joining = false; joinButton.IsEnabled = true; input.IsEnabled = true; }
    }
  }
}
