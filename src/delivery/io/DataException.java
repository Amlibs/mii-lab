package delivery.io;

/** Ошибка в исходных данных: сообщение уже содержит файл, запись и причину. */
public class DataException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DataException(String message) {
        super(message);
    }
}
