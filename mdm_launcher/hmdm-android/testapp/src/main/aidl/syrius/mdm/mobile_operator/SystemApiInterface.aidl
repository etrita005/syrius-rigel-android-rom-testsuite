// SystemApiInterface.aidl
package syrius.mdm.mobile_operator;

// Declare any non-default types here with import statements

interface SystemApiInterface {
    Map onEvent(String event, in Map param);
}
