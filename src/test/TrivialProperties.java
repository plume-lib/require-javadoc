/** Tests the {@code --dont-require-properties} command-line argument. */
class TrivialProperties {

  /** Represents a foo. */
  class Foo {}

  /** dummy comment */
  Foo fooOK;
  /** dummy comment */
  int barOK;
  /** dummy comment */
  boolean bazOK;

  /** dummy comment */
  Foo foo1;
  /** dummy comment */
  int bar1;
  /** dummy comment */
  boolean baz1;

  /** dummy comment */
  Foo foo2;
  /** dummy comment */
  int bar2;
  /** dummy comment */
  boolean baz2;

  /** dummy comment */
  Foo foo3;
  /** dummy comment */
  int bar3;
  /** dummy comment */
  boolean baz3;

  // OK

  public Foo getFooOK() {
    return this.fooOK;
  }

  public void setFooOK(Foo fooOK) {
    this.fooOK = fooOK;
  }

  public int getBarOK() {
    return barOK;
  }

  public void setBarOK(int barOK) {
    this.barOK = barOK;
  }

  public boolean getBazOK() {
    return bazOK;
  }

  public void setBazOK(final boolean bazOK) {
    this.bazOK = bazOK;
  }

  public boolean isBazOK() {
    return bazOK;
  }

  // Not OK

  public int getFoo1() {
    return this.bar1;
  }

  public void setFoo1(int foo1) {
    this.bar1 = foo1;
  }

  public boolean getBar1() {
    return baz1;
  }

  public void setBar1(boolean bar1) {
    baz1 = bar1;
  }

  public Foo isFoo1() {
    return foo1;
  }

  public int isBar1() {
    return bar1;
  }

  public int isBaz1() {
    return bar1;
  }

  public int getFoo2() {
    return this.bar2;
  }

  public void setFoo2(boolean baz2) {
    this.baz2 = baz2;
  }

  public boolean getBar2() {
    return baz2;
  }

  public void setBar2(Foo bar2) {
    foo2 = bar2;
  }

  public boolean isFoo2() {
    return baz2;
  }

  public void isBar2() {}

  public boolean isBaz3() {
    return true;
  }

  // Extra statements

  public Foo getFoo3() {
    System.out.println("called getFoo3");
    return this.foo3;
  }

  public void setFoo3(Foo foo3) {
    System.out.println("called setFoo3");
    this.foo3 = foo3;
  }

  public int getBar3() {
    System.out.println("called getBar3");
    return bar3;
  }

  public void setBar3(int bar3) {
    System.out.println("called setBar3");
    bar3 = bar3;
  }

  // Short method name

  public Foo get() {
    return this.foo1;
  }

  public void set(Foo foo1) {
    this.foo1 = foo1;
  }

  public boolean is() {
    return baz1;
  }
}
