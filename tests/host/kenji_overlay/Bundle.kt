package android.os
class Bundle {
    private val data = mutableMapOf<String, Any>()
    fun putBooleanArray(k: String, v: BooleanArray) { data[k] = v }
    fun putIntArray(k: String, v: IntArray) { data[k] = v }
    fun putString(k: String, v: String) { data[k] = v }
    fun putBoolean(k: String, v: Boolean) { data[k] = v }
    fun putDoubleArray(k: String, v: DoubleArray) { data[k] = v }
    fun putFloat(k: String, v: Float) { data[k] = v }
    fun <T : Any> putParcelableArrayList(k: String, v: ArrayList<T>) { data[k] = v }
    fun getBooleanArray(k: String): BooleanArray? = data[k] as? BooleanArray
    fun getIntArray(k: String): IntArray? = data[k] as? IntArray
    fun getString(k: String): String? = data[k] as? String
    fun getBoolean(k: String): Boolean = data[k] as? Boolean ?: false
    fun getDoubleArray(k: String): DoubleArray? = data[k] as? DoubleArray
    fun getFloat(k: String): Float = data[k] as? Float ?: 0f
    @Suppress("UNCHECKED_CAST") fun <T> getParcelableArrayList(k: String): ArrayList<T>? = data[k] as? ArrayList<T>
}
