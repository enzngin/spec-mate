package specification;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Range<T extends Comparable<? super T>> {
    private T from;
    private T to;
}
