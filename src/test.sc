trait PatchableData[State, Patch] {
  def update(f: State ⇒ Patch): Unit
}

trait Patchable[State, Patch] {
  def update(state: State)(f: State ⇒ Patch): Unit
}

class Example(str: String) extends PatchableData[String, String] {
  override def update(f: String ⇒ String): Unit = println(s"update($f) on $str")
}

//implicit class PData[State, Patch](pdata: PatchableData[State, Patch]) extends Patchable[State, Patch] {
//  override def update(state: State)(f: State ⇒ Patch): Unit = pdata.update(f)
//}

implicit class PData[State, Patch](pdata: PatchableData[State, Patch]) extends Patchable[State, Patch] {
  override def update(state: State)(f: State ⇒ Patch): Unit = pdata.update(f)
}

def method[State, Delta](y: Delta)(implicit P: Patchable[State, Delta]) = {
  P.update(null.asInstanceOf[State])((_: State) ⇒ y)
}

method(new Example("testje"))