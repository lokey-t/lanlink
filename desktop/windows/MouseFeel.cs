using System;
using System.Collections;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Shapes;

namespace LanLink.Desktop {
  // Per-device mouse feel: device rail on the left, the selected device's settings on the right.
  public partial class MainWindow {
    async Task OpenMouseFeel() {
      await Refresh();
      var devices=(Json.Get(State,"mouseDevices") as IEnumerable ?? new object[0]).Cast<object>().ToList();
      var drafts=new Dictionary<string,Dictionary<string,object>>();var changed=new HashSet<string>();
      foreach(var device in devices){var settings=Json.Get(device,"settings");drafts[Json.Text(device,"id")]=new Dictionary<string,object>{{"smooth",Json.Flag(settings,"smooth")},{"reverse",Json.Flag(settings,"reverse")},{"sensitivity",Json.Number(settings,"sensitivity",1)},{"disableAcceleration",Json.Flag(settings,"disableAcceleration")}};}
      var panel=new StackPanel{Margin=new Thickness(24,4,24,20)};var window=Dialog(Locale.T("鼠标手感"),panel,780);window.MinWidth=680;
      panel.Children.Add(LText("选择设备，单独调整它的鼠标体验",13,"#65748B"));
      var grid=new Grid{Margin=new Thickness(0,14,0,0),MinHeight=380};grid.ColumnDefinitions.Add(new ColumnDefinition{Width=new GridLength(232)});grid.ColumnDefinitions.Add(new ColumnDefinition());panel.Children.Add(grid);
      var list=new ListBox{BorderThickness=new Thickness(0),Background=Brushes.Transparent,Padding=new Thickness(6),HorizontalContentAlignment=HorizontalAlignment.Stretch};ScrollViewer.SetHorizontalScrollBarVisibility(list,ScrollBarVisibility.Disabled);ScrollViewer.SetVerticalScrollBarVisibility(list,ScrollBarVisibility.Auto);
      grid.Children.Add(new Border{CornerRadius=new CornerRadius(16),Background=Brush("#F1F5FC"),Margin=new Thickness(0,0,16,0),ClipToBounds=true,Child=list});
      var detail=new StackPanel{Margin=new Thickness(22,18,22,18)};
      var detailCard=new Border{CornerRadius=new CornerRadius(16),Background=Brush("#FFFFFF"),BorderBrush=Brush("#DFE5EF"),BorderThickness=new Thickness(1),Child=new ScrollViewer{Content=detail,VerticalScrollBarVisibility=ScrollBarVisibility.Auto}};Grid.SetColumn(detailCard,1);grid.Children.Add(detailCard);
      foreach(var device in devices)list.Items.Add(new ListBoxItem{Tag=device,Style=(Style)FindResource("DeviceChoice"),Content=MouseFeelDeviceRow(device)});
      var footer=new Grid{Margin=new Thickness(0,16,0,0)};footer.ColumnDefinitions.Add(new ColumnDefinition());footer.ColumnDefinitions.Add(new ColumnDefinition{Width=GridLength.Auto});panel.Children.Add(footer);
      var feedback=LText("更改后点击保存",12,"#65748B");feedback.VerticalAlignment=VerticalAlignment.Center;feedback.TextWrapping=TextWrapping.Wrap;footer.Children.Add(feedback);
      Action dirty=()=>{feedback.Text=Locale.T("有未保存的更改");feedback.Foreground=Brush("#5B6BD5");};
      bool busy=false;
      list.SelectionChanged+=delegate{
        detail.Children.Clear();var item=list.SelectedItem as ListBoxItem;if(item==null)return;var device=item.Tag;string id=Json.Text(device,"id");var draft=drafts[id];
        var header=new Grid{Margin=new Thickness(0,0,0,8)};header.ColumnDefinitions.Add(new ColumnDefinition());header.ColumnDefinitions.Add(new ColumnDefinition{Width=GridLength.Auto});
        var name=Text(Json.Text(device,"name"),18);name.FontWeight=FontWeights.SemiBold;header.Children.Add(name);
        var chip=MouseFeelChip(MouseFeelPlatform(device)+Locale.T(Json.Flag(device,"online")?" · 在线":" · 离线"),Json.Flag(device,"online")?"#2F8566":"#65748B",Json.Flag(device,"online")?"#E3F3ED":"#F1F5FC");Grid.SetColumn(chip,1);header.Children.Add(chip);detail.Children.Add(header);
        if(!Json.Flag(device,"supported")){detail.Children.Add(MouseFeelNote("此设备暂不支持鼠标手感设置"));return;}
        Action guide=()=>Run(async()=>{var result=await Client.Call("mouse-feel-check",new{id});if(Json.Flag(result,"guideRequired"))feedback.Text=Locale.T("请到该设备完成键鼠权限授权，授权后设置生效");});
        Action<string,string> toggle=(key,title)=>{var check=SettingToggle(new CheckBox{IsChecked=Convert.ToBoolean(draft[key])},title,null);check.Click+=delegate{draft[key]=check.IsChecked==true;changed.Add(id);dirty();if(check.IsChecked==true)guide();};detail.Children.Add(check);detail.Children.Add(SettingsDivider());};
        toggle("smooth","平滑滚动");toggle("reverse","反向鼠标滚轮");
        if(Json.Text(device,"platform")=="darwin")toggle("disableAcceleration","关闭鼠标加速度");
        var sensitivity=new Grid{Margin=new Thickness(0,14,0,0)};sensitivity.ColumnDefinitions.Add(new ColumnDefinition());sensitivity.ColumnDefinitions.Add(new ColumnDefinition{Width=GridLength.Auto});
        sensitivity.Children.Add(LText("指针灵敏度",14));
        var slider=new Slider{Minimum=.2,Maximum=3,TickFrequency=.1,IsSnapToTickEnabled=true,Value=Convert.ToDouble(draft["sensitivity"]),Margin=new Thickness(0,10,0,4)};slider.Style=(Style)FindResource("EdgeDelaySlider");
        var value=Text(slider.Value.ToString("0.0")+"×",13,"#5B6BD5");value.FontWeight=FontWeights.SemiBold;
        var valueChip=new Border{Child=value,CornerRadius=new CornerRadius(8),Background=Brush("#ECEEFC"),Padding=new Thickness(10,3,10,3),VerticalAlignment=VerticalAlignment.Center};Grid.SetColumn(valueChip,1);sensitivity.Children.Add(valueChip);
        detail.Children.Add(sensitivity);
        slider.ValueChanged+=delegate{draft["sensitivity"]=slider.Value;changed.Add(id);dirty();value.Text=slider.Value.ToString("0.0")+"×";};slider.PreviewMouseLeftButtonUp+=delegate{if(slider.Value!=1)guide();};detail.Children.Add(slider);
        var limits=new DockPanel();var maximum=Text("3.0×",11,"#65748B");DockPanel.SetDock(maximum,Dock.Right);limits.Children.Add(maximum);limits.Children.Add(Text("0.2×",11,"#65748B"));detail.Children.Add(limits);
        detail.Children.Add(MouseFeelNote(!Json.Flag(device,"online")?"设备当前离线，保存后将在上线时同步应用":Json.Flag(device,"needsPermission")?"请到该设备完成键鼠权限授权，授权后设置生效":Json.Flag(device,"needsUpgrade")?"请升级目标设备以应用鼠标手感设置":"仅调整所选设备，不影响组内其他设备"));
      };
      if(list.Items.Count>0)list.SelectedIndex=0;
      var actions=new StackPanel{Orientation=Orientation.Horizontal,Margin=new Thickness(16,0,0,0)};Grid.SetColumn(actions,1);footer.Children.Add(actions);
      var cancel=LButton("取消",()=>{if(!busy)window.Close();});cancel.MinWidth=96;cancel.Margin=new Thickness(0,0,10,0);actions.Children.Add(cancel);
      var save=LButton("保存设置",()=>Run(async()=>{if(busy)return;if(changed.Count==0){window.Close();return;}busy=true;panel.IsEnabled=false;try{await Client.Call("mouse-feel-save",new{devices=changed.Select(id=>new{id,settings=drafts[id]}).ToArray()});window.Close();await Refresh();}finally{busy=false;panel.IsEnabled=true;}}));save.Style=(Style)FindResource("Primary");save.MinWidth=96;actions.Children.Add(save);window.ShowDialog();
    }
    static string MouseFeelPlatform(object device) { string platform=Json.Text(device,"platform"); return platform=="darwin"?"macOS":platform=="win32"?"Windows":platform=="android"?"Android":platform; }
    FrameworkElement MouseFeelDeviceRow(object device) {
      var row=new Grid();row.ColumnDefinitions.Add(new ColumnDefinition{Width=GridLength.Auto});row.ColumnDefinitions.Add(new ColumnDefinition());
      row.Children.Add(new Border{Width=32,Height=32,CornerRadius=new CornerRadius(9),Background=Brush("#ECEEFC"),Margin=new Thickness(0,0,10,0),VerticalAlignment=VerticalAlignment.Center,Child=QuickGlyph(Json.Text(device,"platform")=="android"?"adb":"default",16,"#5B6BD5")});
      var words=new StackPanel{VerticalAlignment=VerticalAlignment.Center};var name=Text(Json.Text(device,"name"),14);name.FontWeight=FontWeights.SemiBold;words.Children.Add(name);
      var caption=new StackPanel{Orientation=Orientation.Horizontal,Margin=new Thickness(0,4,0,0)};bool online=Json.Flag(device,"online");
      caption.Children.Add(new Ellipse{Width=6,Height=6,Fill=Brush(online?"#2F8566":"#65748B"),Margin=new Thickness(0,0,6,0),VerticalAlignment=VerticalAlignment.Center});
      caption.Children.Add(Text(MouseFeelPlatform(device)+Locale.T(online?" · 在线":" · 离线")+(Json.Flag(device,"master")?Locale.T(" · 主设备"):""),11,"#65748B"));words.Children.Add(caption);
      Grid.SetColumn(words,1);row.Children.Add(words);return row;
    }
    Border MouseFeelChip(string text,string foreground,string background) { return new Border{Child=Text(text,12,foreground),CornerRadius=new CornerRadius(10),Background=Brush(background),Padding=new Thickness(10,4,10,4),VerticalAlignment=VerticalAlignment.Center}; }
    Border MouseFeelNote(string source) {
      var text=LText(source,12,"#65748B");text.TextWrapping=TextWrapping.Wrap;
      return new Border{Child=text,CornerRadius=new CornerRadius(10),Background=Brush("#F1F5FC"),Padding=new Thickness(12,10,12,10),Margin=new Thickness(0,16,0,0)};
    }
  }
}
