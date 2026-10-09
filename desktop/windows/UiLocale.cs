using System;
using System.Collections.Generic;
using System.Windows;
using System.Windows.Controls;

namespace LanLink.Desktop {
  // Only explicitly marked interface strings are bound. Device names, user input,
  // clipboard content and file paths never pass through automatic translation.
  public static class UiLocale {
    sealed class Entry {
      public WeakReference Target;
      public DependencyProperty Property;
      public string Source, Last;
    }
    static readonly List<Entry> entries = new List<Entry>();
    static UiLocale() { Locale.Changed += Refresh; }
    public static T Bind<T>(T element, DependencyProperty property, string source) where T : DependencyObject {
      string translated = Locale.T(source);
      element.SetValue(property, translated);
      entries.Add(new Entry { Target = new WeakReference(element), Property = property, Source = source, Last = translated });
      return element;
    }
    public static TextBlock Content(string source) {
      return Bind(new TextBlock { TextWrapping = TextWrapping.Wrap, VerticalAlignment = VerticalAlignment.Center }, TextBlock.TextProperty, source);
    }
    public static ComboBoxItem Choice(string source, string value) {
      return Bind(new ComboBoxItem { Tag = value }, ContentControl.ContentProperty, source);
    }
    static void Refresh() {
      for (int index = entries.Count - 1; index >= 0; index--) {
        Entry entry = entries[index]; var target = entry.Target.Target as DependencyObject;
        if (target == null) { entries.RemoveAt(index); continue; }
        // A later status or user value supersedes a label's original binding.
        if (!object.Equals(target.GetValue(entry.Property), entry.Last)) { entries.RemoveAt(index); continue; }
        entry.Last = Locale.T(entry.Source); target.SetValue(entry.Property, entry.Last);
      }
    }
  }
}
