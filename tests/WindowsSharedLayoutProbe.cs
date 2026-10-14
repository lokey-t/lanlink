using System;
using LanLink.Desktop;
public class WindowsSharedLayoutProbe {
 static void Require(bool ok,string message){if(!ok)throw new Exception(message);}
 public static void Main(){
  var cells=new[]{new SharedLayoutCell(0,0),new SharedLayoutCell(1,0),new SharedLayoutCell(2,0),new SharedLayoutCell(1,-1),new SharedLayoutCell(2,1)};
  var layout=new SharedLayoutGeometry(840,520,cells);
  foreach(var cell in cells){var actual=layout.Cell(layout.X(cell),layout.Y(cell));Require(actual.HasValue&&actual.Value.X==cell.X&&actual.Value.Y==cell.Y,"Every chain/branch cell must round-trip through drag coordinates");}
  Require(layout.X(cells[2])>layout.X(cells[1])&&layout.X(cells[1])>layout.X(cells[0]),"Multi-level neighbours must retain distinct consecutive positions");
  var down=cells[2].Adjacent("bottom");Require(down.HasValue&&down.Value.X==2&&down.Value.Y==1,"Relative placement from a non-local node");
  var extent=new SharedLayoutGeometry(400,250,new[]{new SharedLayoutCell(-32,-32),new SharedLayoutCell(32,32)});
  Require(extent.Width>400&&extent.Height>250,"Large extents must scroll without shrinking indefinitely");
  Require(!extent.Cell(extent.X(new SharedLayoutCell(33,0)),extent.Y(new SharedLayoutCell(33,0))).HasValue&&!new SharedLayoutCell(32,0).Adjacent("right").HasValue,"Drop and button placement must respect bounds");
  Console.WriteLine("WINDOWS_SHARED_LAYOUT_OK");
 }
}
