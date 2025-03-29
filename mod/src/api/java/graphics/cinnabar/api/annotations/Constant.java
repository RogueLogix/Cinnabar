package graphics.cinnabar.api.annotations;

/*
 * Informs users that the return value of a function is constant over the object's lifetime
 * Functions annotated with this _should_ also be Many thread safe
 *
 * Return value may depend on arguments, in that case equivalent arguments are guaranteed to return equivalent results
 * equivalent purposely left vague here, may depend on implementation. intention still being "cache this result" remains
 */
public @interface Constant {
}
