package krtc.idl

/**
 * Entry point of the raw API. A platform implementation installs its [KrtcFactory] here;
 * constructors and static members of the generated interfaces delegate to it.
 */
public object Krtc {
    private var installed: KrtcFactory? = null

    public var factory: KrtcFactory
        get() = installed ?: error("No krtc platform implementation installed (Krtc.factory is unset)")
        set(value) {
            installed = value
        }
}
