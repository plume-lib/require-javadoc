/** Tests the {@code --dont-require-properties} command-line argument. */
class TrivialProperties {

  /** Represents a foo. */
  class Foo {}

  /** dummy comment */
  Foo fooOK;
  /** dummy comment */
  int barOK;
  /** dummy comment */
  int issueNumberOK;
  /** dummy comment */
  boolean bazOK;
  /** dummy comment */
  boolean quuxOK;
  /** dummy comment */
  boolean isEnabled;
  /** dummy comment */
  boolean enabled;

  /** dummy comment */
  Foo foo1;
  /** dummy comment */
  int bar1;
  /** dummy comment */
  boolean baz1;
  /** dummy comment */
  boolean quux1;
  /** dummy comment */
  boolean isEnabled1;
  /** dummy comment */
  boolean enabled1;

  /** dummy comment */
  Foo foo2;
  /** dummy comment */
  int bar2;
  /** dummy comment */
  boolean baz2;
  /** dummy comment */
  boolean quux2;

  /** dummy comment */
  Foo foo3;
  /** dummy comment */
  int bar3;
  /** dummy comment */
  boolean baz3;
  /** dummy comment */
  boolean quux3;

  // OK

  public Foo getFooOK() {
    return this.fooOK;
  }

  public Foo fooOK() {
    return this.fooOK;
  }

  public void setFooOK(Foo fooOK) {
    this.fooOK = fooOK;
  }

  public int getBarOK() {
    return barOK;
  }

  public int barOK() {
    return barOK;
  }

  public void setBarOK(int barOK) {
    this.barOK = barOK;
  }

  public int getIssueNumberOK() {
    return issueNumberOK;
  }

  public int issueNumberOK() {
    return issueNumberOK;
  }

  public void setIssueNumberOK(int issueNumberOK) {
    this.issueNumberOK = issueNumberOK;
  }

  public boolean getBazOK() {
    return bazOK;
  }

  public void setBazOK(final boolean bazOK) {
    this.bazOK = bazOK;
  }

  public boolean hasBazOK() {
    return bazOK;
  }

  public boolean isBazOK() {
    return bazOK;
  }

  public boolean notBazOK() {
    return !bazOK;
  }

  public boolean hasQuuxOK() {
    return this.quuxOK;
  }

  public boolean isQuuxOK() {
    return this.quuxOK;
  }

  public boolean notQuuxOK() {
    return !this.quuxOK;
  }

  public boolean getIsEnabledOK() {
    return isEnabledOK;
  }

  public void setIsEnabledOK(final boolean isEnabledOK) {
    this.isEnabledOK = isEnabledOK;
  }

  public boolean hasIsEnabledOK() {
    return isEnabledOK;
  }

  public boolean isIsEnabledOK() {
    return isEnabledOK;
  }

  public boolean notIsEnabledOK() {
    return !isEnabledOK;
  }

  public boolean getEnabledOK() {
    return enabledOK;
  }

  public void setEnabledOK(final boolean enabledOK) {
    this.enabledOK = enabledOK;
  }

  public boolean hasEnabledOK() {
    return enabledOK;
  }

  public boolean isEnabledOK() {
    return enabledOK;
  }

  public boolean notEnabledOK() {
    return !enabledOK;
  }

  // Not OK

  public int getFoo1() {
    return this.bar1;
  }

  public int foo1() {
    return this.bar1;
  }

  public void setFoo1(int foo1) {
    this.bar1 = foo1;
  }

  public boolean getBar1() {
    return baz1;
  }

  public boolean bar1() {
    return baz1;
  }

  public void setBar1(boolean bar1) {
    baz1 = bar1;
  }

  public boolean getIssueNumber1() {
    return baz1;
  }

  public boolean issueNumber1() {
    return baz1;
  }

  public void setIssueNumber1(boolean issueNumber1) {
    baz1 = issueNumber1;
  }

  public Foo hasFoo1() {
    return foo1;
  }

  public int hasBar1() {
    return bar1;
  }

  public int hasIssueNumber1() {
    return issueNumber1;
  }

  public int hasBaz1() {
    return bar1;
  }

  public int hasEnabled1() {
    return isEnabled1;
  }

  public int hasIsEnabled1() {
    return enabled1;
  }

  public Foo isFoo1() {
    return foo1;
  }

  public int isBar1() {
    return bar1;
  }

  public int isIssueNumber1() {
    return issueNumber1;
  }

  public int isBaz1() {
    return bar1;
  }

  public Foo notFoo1() {
    return foo1;
  }

  public int notBar1() {
    return bar1;
  }

  public int notBaz1() {
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

  public boolean hasFoo2() {
    return baz2;
  }

  public void hasBar2() {}

  public boolean hasBaz2() {
    return true;
  }

  public boolean isFoo2() {
    return baz2;
  }

  public void isBar2() {}

  public boolean isBaz2() {
    return true;
  }

  public boolean notBaz2() {
    return baz2;
  }

  public boolean notQuux2() {
    return this.quux2;
  }

  // Wrong body

  public boolean notFoo2() {
    return baz2;
  }

  public void notBar2() {}

  // Extra statements

  public Foo getFoo3() {
    System.out.println("called getFoo3");
    return this.foo3;
  }

  public Foo foo3() {
    System.out.println("called foo3");
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

  public int bar3() {
    System.out.println("called bar3");
    return bar3;
  }

  public void setBar3(int bar3) {
    System.out.println("called setBar3");
    bar3 = bar3;
  }

  public boolean isBaz3() {
    return !baz3;
  }

  public boolean notBaz3() {
    return !(baz3);
  }

  public boolean isQuux3() {
    return !this.quux3;
  }

  public boolean notQuux3() {
    return (!this.quux3);
  }

  // Short method name

  public Foo get() {
    return this.foo1;
  }

  public void set(Foo foo1) {
    this.foo1 = foo1;
  }

  public boolean has() {
    return baz1;
  }

  public boolean is() {
    return baz1;
  }
}
